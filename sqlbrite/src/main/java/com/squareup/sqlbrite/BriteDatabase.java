/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.sqlbrite;

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteTransactionListener;
import android.support.annotation.CheckResult;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import com.squareup.sqlbrite.SqlBrite.Query;

import java.io.Closeable;
import java.lang.annotation.Retention;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Func1;
import rx.subjects.PublishSubject;

import static android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT;
import static android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL;
import static android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE;
import static android.database.sqlite.SQLiteDatabase.CONFLICT_NONE;
import static android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE;
import static android.database.sqlite.SQLiteDatabase.CONFLICT_ROLLBACK;
import static android.os.Build.VERSION_CODES.HONEYCOMB;
import static java.lang.System.nanoTime;
import static java.lang.annotation.RetentionPolicy.SOURCE;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * A lightweight wrapper around {@link SQLiteOpenHelper} which allows for continuously observing
 * the result of a query. Create using a {@link SqlBrite} instance.
 */
public final class BriteDatabase implements Closeable {
  private final SQLiteOpenHelper helper;
  private final SqlBrite.Logger logger;

  // Package-private to avoid synthetic accessor method for 'transaction' instance.
  final ThreadLocal<SqliteTransaction> transactions = new ThreadLocal<>();
  /** Publishes sets of tables which have changed. */
  private final PublishSubject<Set<CommandWraper>> triggers = PublishSubject.create();

  private final TableMapper tableMapper = new TableMapper();

  private final Transaction transaction = new Transaction() {
    @Override public void markSuccessful() {
      if (logging) log("TXN SUCCESS %s", transactions.get());
      getWriteableDatabase().setTransactionSuccessful();
    }

    @Override public boolean yieldIfContendedSafely() {
      return getWriteableDatabase().yieldIfContendedSafely();
    }

    @Override public boolean yieldIfContendedSafely(long sleepAmount, TimeUnit sleepUnit) {
      return getWriteableDatabase().yieldIfContendedSafely(sleepUnit.toMillis(sleepAmount));
    }

    @Override public void end() {
      SqliteTransaction transaction = transactions.get();
      if (transaction == null) {
        throw new IllegalStateException("Not in transaction.");
      }
      SqliteTransaction newTransaction = transaction.parent;
      transactions.set(newTransaction);
      if (logging) log("TXN END %s", transaction);
      getWriteableDatabase().endTransaction();
      // Send the triggers after ending the transaction in the DB.
      if (transaction.commit) {
        sendTableTrigger(transaction);
      }
    }

    @Override public void close() {
      end();
    }
  };
  private final Action0 ensureNotInTransaction = new Action0() {
    @Override public void call() {
      if (transactions.get() != null) {
        throw new IllegalStateException("Cannot subscribe to observable query in a transaction.");
      }
    }
  };

  // Read and write guarded by 'databaseLock'. Lazily initialized. Use methods to access.
  private volatile SQLiteDatabase readableDatabase;
  private volatile SQLiteDatabase writeableDatabase;
  private final Object databaseLock = new Object();

  private final Scheduler scheduler;

  // Package-private to avoid synthetic accessor method for 'transaction' instance.
  volatile boolean logging;

  BriteDatabase(SQLiteOpenHelper helper, SqlBrite.Logger logger, Scheduler scheduler) {
    this.helper = helper;
    this.logger = logger;
    this.scheduler = scheduler;
  }

  /**
   * Control whether debug logging is enabled.
   */
  public void setLoggingEnabled(boolean enabled) {
    logging = enabled;
  }

  SQLiteDatabase getReadableDatabase() {
    SQLiteDatabase db = readableDatabase;
    if (db == null) {
      synchronized (databaseLock) {
        db = readableDatabase;
        if (db == null) {
          if (logging) log("Creating readable database");
          db = readableDatabase = helper.getReadableDatabase();
        }
      }
    }
    return db;
  }

  // Package-private to avoid synthetic accessor method for 'transaction' instance.
  SQLiteDatabase getWriteableDatabase() {
    SQLiteDatabase db = writeableDatabase;
    if (db == null) {
      synchronized (databaseLock) {
        db = writeableDatabase;
        if (db == null) {
          if (logging) log("Creating writeable database");
          db = writeableDatabase = helper.getWritableDatabase();
        }
      }
    }
    return db;
  }

  void sendTableTrigger(Set<CommandWraper> tables) {
    SqliteTransaction transaction = transactions.get();
    if (transaction != null) {
      transaction.addAll(tables);
    } else {
      if (logging) log("TRIGGER %s", tables);
      triggers.onNext(tables);
    }
  }

  /**
   * Begin a transaction for this thread.
   * <p>
   * Transactions may nest. If the transaction is not in progress, then a database connection is
   * obtained and a new transaction is started. Otherwise, a nested transaction is started.
   * <p>
   * Each call to {@code newTransaction} must be matched exactly by a call to
   * {@link Transaction#end()}. To mark a transaction as successful, call
   * {@link Transaction#markSuccessful()} before calling {@link Transaction#end()}. If the
   * transaction is not successful, or if any of its nested transactions were not successful, then
   * the entire transaction will be rolled back when the outermost transaction is ended.
   * <p>
   * Transactions queue up all query notifications until they have been applied.
   * <p>
   * Here is the standard idiom for transactions:
   *
   * <pre>{@code
   * try (Transaction transaction = db.newTransaction()) {
   *   ...
   *   transaction.markSuccessful();
   * }
   * }</pre>
   *
   * Manually call {@link Transaction#end()} when try-with-resources is not available:
   * <pre>{@code
   * Transaction transaction = db.newTransaction();
   * try {
   *   ...
   *   transaction.markSuccessful();
   * } finally {
   *   transaction.end();
   * }
   * }</pre>
   *
   *
   * @see SQLiteDatabase#beginTransaction()
   */
  @CheckResult @NonNull
  public Transaction newTransaction() {
    SqliteTransaction transaction = new SqliteTransaction(transactions.get());
    transactions.set(transaction);
    if (logging) log("TXN BEGIN %s", transaction);
    getWriteableDatabase().beginTransactionWithListener(transaction);

    return this.transaction;
  }

  /**
   * Begins a transaction in IMMEDIATE mode for this thread.
   * <p>
   * Transactions may nest. If the transaction is not in progress, then a database connection is
   * obtained and a new transaction is started. Otherwise, a nested transaction is started.
   * <p>
   * Each call to {@code newNonExclusiveTransaction} must be matched exactly by a call to
   * {@link Transaction#end()}. To mark a transaction as successful, call
   * {@link Transaction#markSuccessful()} before calling {@link Transaction#end()}. If the
   * transaction is not successful, or if any of its nested transactions were not successful, then
   * the entire transaction will be rolled back when the outermost transaction is ended.
   * <p>
   * Transactions queue up all query notifications until they have been applied.
   * <p>
   * Here is the standard idiom for transactions:
   *
   * <pre>{@code
   * try (Transaction transaction = db.newNonExclusiveTransaction()) {
   *   ...
   *   transaction.markSuccessful();
   * }
   * }</pre>
   *
   * Manually call {@link Transaction#end()} when try-with-resources is not available:
   * <pre>{@code
   * Transaction transaction = db.newNonExclusiveTransaction();
   * try {
   *   ...
   *   transaction.markSuccessful();
   * } finally {
   *   transaction.end();
   * }
   * }</pre>
   *
   *
   * @see SQLiteDatabase#beginTransactionNonExclusive()
   */
  @TargetApi(HONEYCOMB)
  @CheckResult @NonNull
  public Transaction newNonExclusiveTransaction() {
    SqliteTransaction transaction = new SqliteTransaction(transactions.get());
    transactions.set(transaction);
    if (logging) log("TXN BEGIN %s", transaction);
    getWriteableDatabase().beginTransactionWithListenerNonExclusive(transaction);

    return this.transaction;
  }

  /**
   * Close the underlying {@link SQLiteOpenHelper} and remove cached readable and writeable
   * databases. This does not prevent existing observables from retaining existing references as
   * well as attempting to create new ones for new subscriptions.
   */
  @Override public void close() {
    synchronized (databaseLock) {
      readableDatabase = null;
      writeableDatabase = null;
      helper.close();
    }
  }

  /**
   * Create an observable which will notify subscribers with a {@linkplain Query query} for
   * execution. Subscribers are responsible for <b>always</b> closing {@link Cursor} instance
   * returned from the {@link Query}.
   * <p>
   * Subscribers will receive an immediate notification for initial data as well as subsequent
   * notifications for when the supplied {@code table}'s data changes through the {@code insert},
   * {@code update}, and {@code delete} methods of this class. Unsubscribe when you no longer want
   * updates to a query.
   * <p>
   * Since database triggers are inherently asynchronous, items emitted from the returned
   * observable use the {@link Scheduler} supplied to {@link SqlBrite#wrapDatabaseHelper}. For
   * consistency, the immediate notification sent on subscribe also uses this scheduler. As such,
   * calling {@link Observable#subscribeOn subscribeOn} on the returned observable has no effect.
   * <p>
   * Note: To skip the immediate notification and only receive subsequent notifications when data
   * has changed call {@code skip(1)} on the returned observable.
   * <p>
   * <b>Warning:</b> this method does not perform the query! Only by subscribing to the returned
   * {@link Observable} will the operation occur.
   *
   * @see SQLiteDatabase#rawQuery(String, String[])
   */
  @CheckResult @NonNull
  public QueryObservable createQuery(@NonNull final String table, @NonNull String sql,
                                     @NonNull String... args) {
    Func1<Set<CommandWraper>, Boolean> tableFilter = new Func1<Set<CommandWraper>, Boolean>() {
      @Override public Boolean call(Set<CommandWraper> triggers) {

        for(CommandWraper next : triggers){
          if(next.table.equalsIgnoreCase(table)){
            return true;
          }
        }
        return false;
      }

      @Override public String toString() {
        return table;
      }
    };
    return createQuery(tableFilter, sql, args);
  }

  /**
   * See {@link #createQuery(String, String, String...)} for usage. This overload allows for
   * monitoring multiple tables for changes.
   *
   * @see SQLiteDatabase#rawQuery(String, String[])
   */
  @CheckResult @NonNull
  public QueryObservable createQuery(@NonNull final Iterable<String> tables, @NonNull String sql,
                                     @NonNull String... args) {
    Func1<Set<CommandWraper>, Boolean> tableFilter = new Func1<Set<CommandWraper>, Boolean>() {
      @Override public Boolean call(Set<CommandWraper> triggers) {
        for (String table : tables) {
          for(CommandWraper next : triggers){
            if(next.table.equalsIgnoreCase(table)){
              return true;
            }
          }
        }
        return false;
      }

      @Override public String toString() {
        return tables.toString();
      }
    };
    return createQuery(tableFilter, sql, args);
  }

  @CheckResult @NonNull
  private QueryObservable createQuery(Func1<Set<CommandWraper>, Boolean> tableFilter, String sql,
                                      String... args) {
    if (transactions.get() != null) {
      throw new IllegalStateException("Cannot create observable query in transaction. "
              + "Use query() for a query inside a transaction.");
    }

    DatabaseQuery query = new DatabaseQuery(tableFilter, sql, args);
    final Observable<Query> queryObservable = triggers //
            .filter(tableFilter) // Only trigger on tables we care about.
            .map(query) // DatabaseQuery maps to itself to save an allocation.
            .onBackpressureLatest() // Guard against uncontrollable frequency of upstream emissions.
            .startWith(query) //
            .observeOn(scheduler) //
            .onBackpressureLatest() // Guard against uncontrollable frequency of scheduler executions.
            .doOnSubscribe(ensureNotInTransaction);
    // TODO switch to .extend when non-@Experimental
    return new QueryObservable(new Observable.OnSubscribe<Query>() {
      @Override public void call(Subscriber<? super Query> subscriber) {
        queryObservable.unsafeSubscribe(subscriber);
      }
    });
  }

  public BriteDatabase registMapper(final String table, String primaryKey){
    tableMapper.put(table, primaryKey);
    return this;
  }

  public BriteDatabase unregistMapper(final String table, String primaryKey){
    tableMapper.put(table, primaryKey);
    return this;
  }

  public QueryObservable listener(@NonNull final String table) {
    if(!tableMapper.containsKey(table)){
      registMapper(table, "rowid");
      if(logging){
        log("Can't find %s primary key. Use default rowid", table);
      }
    }
    Func1<Set<CommandWraper>, Boolean> tableFilter = new Func1<Set<CommandWraper>, Boolean>() {
      @Override public Boolean call(Set<CommandWraper> triggers) {

        for(CommandWraper next : triggers){
          if(next.table.equalsIgnoreCase(table)){
            return true;
          }
        }
        return false;
      }

      @Override public String toString() {
        return table;
      }
    };
    return listener(tableFilter);
  }

  public QueryObservable listener(@NonNull final String table, final Command cmd) {
    if(!tableMapper.containsKey(table)){
      registMapper(table, "rowid");
      if(logging){
        log("Can't find %s primary key. Use default rowid", table);
      }
    }
    Func1<Set<CommandWraper>, Boolean> tableFilter = new Func1<Set<CommandWraper>, Boolean>() {
      @Override public Boolean call(Set<CommandWraper> triggers) {

        for(CommandWraper next : triggers){
          if(next.table.equalsIgnoreCase(table) && next.cmd == cmd){
            return true;
          }
        }
        return false;
      }

      @Override public String toString() {
        return table;
      }
    };
    return listener(tableFilter);
  }

  public QueryObservable listener(@NonNull final String table, final Command cmd, final int columnValue) {
    return listener(table, cmd, "rowid", columnValue);
  }

  public QueryObservable listener(@NonNull final String table, final Command cmd, final String columnName, final Object columnValue) {
    if(TextUtils.isEmpty(columnName) || columnValue == null){
      throw new RuntimeException("ColumnName cannot be empty.");
    }

    if(!tableMapper.containsKey(table)){
      registMapper(table, columnName);
    }

    Func1<Set<CommandWraper>, Boolean> tableFilter = new Func1<Set<CommandWraper>, Boolean>() {
      @Override public Boolean call(Set<CommandWraper> triggers) {

        for(CommandWraper next : triggers){
          if(next.table.equalsIgnoreCase(table) && next.cmd == cmd){
            Cursor cursor = next.result;
            if(cursor == null || cursor.getCount() == 0){
              return false;
            }
            final int columnIndex = cursor.getColumnIndex(columnName);
            for(cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()){
              if(cursor.getString(columnIndex).equals(String.valueOf(columnValue))){
                return true;
              }
            }
            return false;
          }
        }
        return false;
      }

      @Override public String toString() {
        return table;
      }
    };
    return listener(tableFilter);
  }

  public QueryObservable listener(@NonNull final String table, final int columnValue) {
    return listener(table, "rowid", columnValue);
  }

  public QueryObservable listener(@NonNull final String table, final String columnName,final Object columnValue) {
    if(TextUtils.isEmpty(columnName) || columnValue == null){
      throw new RuntimeException("ColumnName cannot be empty.");
    }

    if(!tableMapper.containsKey(table)){
      registMapper(table, columnName);
    }

    Func1<Set<CommandWraper>, Boolean> tableFilter = new Func1<Set<CommandWraper>, Boolean>() {
      @Override public Boolean call(Set<CommandWraper> triggers) {

        for(CommandWraper next : triggers){
          if(next.table.equalsIgnoreCase(table)){
            Cursor cursor = next.result;
            if(cursor == null || cursor.getCount() == 0){
              return false;
            }
            final int columnIndex = cursor.getColumnIndex(columnName);
            for(cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()){
              if(cursor.getString(columnIndex).equals(String.valueOf(columnValue))){
                return true;
              }
            }
            return false;
          }
        }
        return false;
      }

      @Override public String toString() {
        return table;
      }
    };
    return listener(tableFilter);
  }

  @CheckResult @NonNull
  private QueryObservable listener(Func1<Set<CommandWraper>, Boolean> tableFilter) {
    if (transactions.get() != null) {
      throw new IllegalStateException("Cannot create observable query in transaction. "
              + "Use query() for a query inside a transaction.");
    }

    ActionWraper query = new ActionWraper(tableFilter);
    final Observable<Query> queryObservable = triggers //
            .filter(tableFilter) // Only trigger on tables we care about.
            .map(query) // DatabaseQuery maps to itself to save an allocation.
            .onBackpressureLatest() // Guard against uncontrollable frequency of upstream emissions.
            //.startWith(query) //
            .observeOn(scheduler) //
            .onBackpressureLatest() // Guard against uncontrollable frequency of scheduler executions.
            .doOnSubscribe(ensureNotInTransaction);
    // TODO switch to .extend when non-@Experimental
    return new QueryObservable(new Observable.OnSubscribe<Query>() {
      @Override public void call(Subscriber<? super Query> subscriber) {
        queryObservable.unsafeSubscribe(subscriber);
      }
    });
  }

  /**
   * Runs the provided SQL and returns a {@link Cursor} over the result set.
   *
   * @see SQLiteDatabase#rawQuery(String, String[])
   */
  @CheckResult // TODO @WorkerThread
  public Cursor query(@NonNull String sql, @NonNull String... args) {
    long startNanos = nanoTime();
    Cursor cursor = getReadableDatabase().rawQuery(sql, args);
    long tookMillis = NANOSECONDS.toMillis(nanoTime() - startNanos);

    if (logging) {
      log("QUERY (%sms)\n  sql: %s\n  args: %s", tookMillis, indentSql(sql), Arrays.toString(args));
    }

    return cursor;
  }

  /**
   * Insert a row into the specified {@code table} and notify any subscribed queries.
   *
   * @see SQLiteDatabase#insert(String, String, ContentValues)
   */
  // TODO @WorkerThread
  public long insert(@NonNull String table, @NonNull ContentValues values) {
    return insert(table, values, CONFLICT_NONE);
  }

  /**
   * Insert a row into the specified {@code table} and notify any subscribed queries.
   *
   * @see SQLiteDatabase#insertWithOnConflict(String, String, ContentValues, int)
   */
  // TODO @WorkerThread
  public long insert(@NonNull String table, @NonNull ContentValues values,
                     @ConflictAlgorithm int conflictAlgorithm) {
    SQLiteDatabase db = getWriteableDatabase();

    if (logging) {
      log("INSERT\n  table: %s\n  values: %s\n  conflictAlgorithm: %s", table, values,
              conflictString(conflictAlgorithm));
    }

    long rowId = db.insertWithOnConflict(table, null, values, conflictAlgorithm);

    Cursor result = null;
    if(tableMapper.containsKey(table)){
      result = queryWraper(table, "rowid=?", String.valueOf(rowId));
    }

    if (logging) log("INSERT rowid: %s", rowId);

    if (rowId != -1 && result != null) {
      // Only send a table trigger if the insert was successful.
      final CommandWraper wraper = CommandWraper.insert(table);
      wraper.result = result;
      wraper.sql = "SELECT rowid, * FROM " + table + " WHERE rowid =?";
      wraper.whereArgs = new String[]{String.valueOf(rowId)};
      sendTableTrigger(Collections.singleton(wraper));
    }
    return rowId;
  }

  /**
   * Delete rows from the specified {@code table} and notify any subscribed queries. This method
   * will not trigger a notification if no rows were deleted.
   *
   * @see SQLiteDatabase#delete(String, String, String[])
   */
  // TODO @WorkerThread
  public int delete(@NonNull String table, @Nullable String whereClause,
                    @Nullable String... whereArgs) {
    SQLiteDatabase db = getWriteableDatabase();

    if (logging) {
      log("DELETE\n  table: %s\n  whereClause: %s\n  whereArgs: %s", table, whereClause,
              Arrays.toString(whereArgs));
    }

    MatrixCursor matrixCursor = null;
    if(tableMapper.containsKey(table)){
      matrixCursor = queryWraper(table, whereClause, whereArgs);
      if(matrixCursor == null) return -1;
    }

    int rows = db.delete(table, whereClause, whereArgs);

    if (logging) log("DELETE affected %s %s", rows, rows != 1 ? "rows" : "row");

    if (rows > 0 && matrixCursor != null) {
      // Only send a table trigger if rows were affected.
      final CommandWraper wraper = CommandWraper.delete(table, whereClause, whereArgs);
      wraper.result = matrixCursor;
      sendTableTrigger(Collections.singleton(wraper));
    }
    return rows;
  }

  /**
   * Update rows in the specified {@code table} and notify any subscribed queries. This method
   * will not trigger a notification if no rows were updated.
   *
   * @see SQLiteDatabase#update(String, ContentValues, String, String[])
   */
  // TODO @WorkerThread
  public int update(@NonNull String table, @NonNull ContentValues values,
                    @Nullable String whereClause, @Nullable String... whereArgs) {
    return update(table, values, CONFLICT_NONE, whereClause, whereArgs);
  }

  /**
   * Update rows in the specified {@code table} and notify any subscribed queries. This method
   * will not trigger a notification if no rows were updated.
   *
   * @see SQLiteDatabase#updateWithOnConflict(String, ContentValues, String, String[], int)
   */
  // TODO @WorkerThread
  public int update(@NonNull String table, @NonNull ContentValues values,
                    @ConflictAlgorithm int conflictAlgorithm, @Nullable String whereClause,
                    @Nullable String... whereArgs) {
    SQLiteDatabase db = getWriteableDatabase();

    if (logging) {
      log("UPDATE\n  table: %s\n  values: %s\n  whereClause: %s\n  whereArgs: %s\n  conflictAlgorithm: %s",
              table, values, whereClause, Arrays.toString(whereArgs),
              conflictString(conflictAlgorithm));
    }

    MatrixCursor matrixCursor = null;
    if(tableMapper.containsKey(table)){
      matrixCursor = queryWraper(table, whereClause, whereArgs);
      if(matrixCursor == null) return -1;
    }

    int rows = db.updateWithOnConflict(table, values, whereClause, whereArgs, conflictAlgorithm);

    if (logging) log("UPDATE affected %s %s", rows, rows != 1 ? "rows" : "row");

    if (rows > 0) {
      // Only send a table trigger if rows were affected.
      final CommandWraper wraper = CommandWraper.update(table, whereClause, whereArgs);
      wraper.result = matrixCursor;
      sendTableTrigger(Collections.singleton(wraper));
    }
    return rows;
  }

  private MatrixCursor queryWraper(String table, String whereClause, String... whereArgs){
    MatrixCursor matrixCursor = null;

    final String sql = new StringBuilder()
            .append("SELECT ")
            .append("rowid as rowid, *")
            .append(" FROM ")
            .append(table)
            .append(" WHERE ")
            .append(whereClause)
            .toString();

    Cursor cursor = null;
    try{

      long startNanos = nanoTime();
      cursor = getReadableDatabase().rawQuery(sql, whereArgs);

      if (logging) {
        long tookMillis = NANOSECONDS.toMillis(nanoTime() - startNanos);
        log("QUERY (%sms)\n  sql: %s\n  args: %s", tookMillis, indentSql(sql), Arrays.toString(whereArgs));
      }

      if(cursor == null || cursor.getCount() <= 0) return null;

      final int columnCount = cursor.getColumnNames().length;
      matrixCursor = new MatrixCursor(cursor.getColumnNames(), cursor.getCount());
      for(cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()){
        Object[] values = new Object[columnCount];
        for(int j=0; j<columnCount; j++){
          values[j] = cursor.getString(j);
        }
        matrixCursor.addRow(values);
      }

      if (logging) {
        int rows = matrixCursor.getCount();
        log("QUERYWRAPER affected %s %s", rows, rows != 1 ? "rows" : "row");
      }
    }finally {

      if(cursor != null){
        cursor.close();
      }
    }
    return matrixCursor;
  }

  /**
   * Execute {@code sql} provided it is NOT a {@code SELECT} or any other SQL statement that
   * returns data. No data can be returned (such as the number of affected rows). Instead, use
   * {@link #insert}, {@link #update}, et al, when possible.
   * <p>
   * No notifications will be sent to queries if {@code sql} affects the data of a table.
   *
   * @see SQLiteDatabase#execSQL(String)
   */
  public void execute(String sql) {
    if (logging) log("EXECUTE\n  sql: %s", sql);

    SQLiteDatabase db = getWriteableDatabase();
    db.execSQL(sql);
  }

  /**
   * Execute {@code sql} provided it is NOT a {@code SELECT} or any other SQL statement that
   * returns data. No data can be returned (such as the number of affected rows). Instead, use
   * {@link #insert}, {@link #update}, et al, when possible.
   * <p>
   * No notifications will be sent to queries if {@code sql} affects the data of a table.
   *
   * @see SQLiteDatabase#execSQL(String, Object[])
   */
  public void execute(String sql, Object... args) {
    if (logging) log("EXECUTE\n  sql: %s\n  args: %s", sql, Arrays.toString(args));

    SQLiteDatabase db = getWriteableDatabase();
    db.execSQL(sql, args);
  }

  /**
   * Execute {@code sql} provided it is NOT a {@code SELECT} or any other SQL statement that
   * returns data. No data can be returned (such as the number of affected rows). Instead, use
   * {@link #insert}, {@link #update}, et al, when possible.
   * <p>
   * A notification to queries for {@code table} will be sent after the statement is executed.
   *
   * @see SQLiteDatabase#execSQL(String)
   */
  public void executeAndTrigger(String table, String sql) {
    execute(sql);

    sendTableTrigger(Collections.singleton(CommandWraper.unknown(table)));
  }

  /**
   * Execute {@code sql} provided it is NOT a {@code SELECT} or any other SQL statement that
   * returns data. No data can be returned (such as the number of affected rows). Instead, use
   * {@link #insert}, {@link #update}, et al, when possible.
   * <p>
   * A notification to queries for {@code table} will be sent after the statement is executed.
   *
   * @see SQLiteDatabase#execSQL(String, Object[])
   */
  public void executeAndTrigger(String table, String sql, Object... args) {
    execute(sql, args);

    sendTableTrigger(Collections.singleton(CommandWraper.unknown(table)));
  }

  /** An in-progress database transaction. */
  public interface Transaction extends Closeable {
    /**
     * End a transaction. See {@link #newTransaction()} for notes about how to use this and when
     * transactions are committed and rolled back.
     *
     * @see SQLiteDatabase#endTransaction()
     */
    // TODO @WorkerThread
    void end();

    /**
     * Marks the current transaction as successful. Do not do any more database work between
     * calling this and calling {@link #end()}. Do as little non-database work as possible in that
     * situation too. If any errors are encountered between this and {@link #end()} the transaction
     * will still be committed.
     *
     * @see SQLiteDatabase#setTransactionSuccessful()
     */
    // TODO @WorkerThread
    void markSuccessful();

    /**
     * Temporarily end the transaction to let other threads run. The transaction is assumed to be
     * successful so far. Do not call {@link #markSuccessful()} before calling this. When this
     * returns a new transaction will have been created but not marked as successful. This assumes
     * that there are no nested transactions (newTransaction has only been called once) and will
     * throw an exception if that is not the case.
     *
     * @return true if the transaction was yielded
     *
     * @see SQLiteDatabase#yieldIfContendedSafely()
     */
    // TODO @WorkerThread
    boolean yieldIfContendedSafely();

    /**
     * Temporarily end the transaction to let other threads run. The transaction is assumed to be
     * successful so far. Do not call {@link #markSuccessful()} before calling this. When this
     * returns a new transaction will have been created but not marked as successful. This assumes
     * that there are no nested transactions (newTransaction has only been called once) and will
     * throw an exception if that is not the case.
     *
     * @param sleepAmount if > 0, sleep this long before starting a new transaction if
     *   the lock was actually yielded. This will allow other background threads to make some
     *   more progress than they would if we started the transaction immediately.
     * @return true if the transaction was yielded
     *
     * @see SQLiteDatabase#yieldIfContendedSafely(long)
     */
    // TODO @WorkerThread
    boolean yieldIfContendedSafely(long sleepAmount, TimeUnit sleepUnit);

    /**
     * Equivalent to calling {@link #end()}
     */
    // TODO @WorkerThread
    @Override void close();
  }

  @IntDef({
          CONFLICT_ABORT,
          CONFLICT_FAIL,
          CONFLICT_IGNORE,
          CONFLICT_NONE,
          CONFLICT_REPLACE,
          CONFLICT_ROLLBACK
  })
  @Retention(SOURCE)
  public @interface ConflictAlgorithm {
  }

  private static String indentSql(String sql) {
    return sql == null ? "" : sql.replace("\n", "\n       ");
  }

  void log(String message, Object... args) {
    if (args.length > 0) message = String.format(message, args);
    logger.log(message);
  }

  private static String conflictString(@ConflictAlgorithm int conflictAlgorithm) {
    switch (conflictAlgorithm) {
      case CONFLICT_ABORT:
        return "abort";
      case CONFLICT_FAIL:
        return "fail";
      case CONFLICT_IGNORE:
        return "ignore";
      case CONFLICT_NONE:
        return "none";
      case CONFLICT_REPLACE:
        return "replace";
      case CONFLICT_ROLLBACK:
        return "rollback";
      default:
        return "unknown (" + conflictAlgorithm + ')';
    }
  }

  static final class SqliteTransaction extends LinkedHashSet<CommandWraper>
          implements SQLiteTransactionListener {
    final SqliteTransaction parent;
    boolean commit;

    SqliteTransaction(SqliteTransaction parent) {
      this.parent = parent;
    }

    @Override public void onBegin() {
    }

    @Override public void onCommit() {
      commit = true;
    }

    @Override public void onRollback() {
    }

    @Override public String toString() {
      String name = String.format("%08x", System.identityHashCode(this));
      return parent == null ? name : name + " [" + parent.toString() + ']';
    }
  }

  final class DatabaseQuery extends Query implements Func1<Set<CommandWraper>, Query> {
    private final Func1<Set<CommandWraper>, Boolean> tableFilter;
    private final String sql;
    private final String[] args;

    DatabaseQuery(Func1<Set<CommandWraper>, Boolean> tableFilter, String sql, String... args) {
      this.tableFilter = tableFilter;
      this.sql = sql;
      this.args = args;
    }

    @Override public Cursor run() {
      if (transactions.get() != null) {
        throw new IllegalStateException("Cannot execute observable query in a transaction.");
      }

      long startNanos = nanoTime();
      Cursor cursor = getReadableDatabase().rawQuery(sql, args);

      if (logging) {
        long tookMillis = NANOSECONDS.toMillis(nanoTime() - startNanos);
        log("QUERY (%sms)\n  tables: %s\n  sql: %s\n  args: %s", tookMillis, tableFilter,
                indentSql(sql), Arrays.toString(args));
      }

      return cursor;
    }

    @Override public String toString() {
      return sql;
    }

    @Override public Query call(Set<CommandWraper> ignored) {
      return this;
    }
  }

  public class ActionWraper extends Query implements Func1<Set<CommandWraper>, Query> {
    private final Func1<Set<CommandWraper>, Boolean> tableFilter;
    private CommandWraper mNextWraper;

    ActionWraper(Func1<Set<CommandWraper>, Boolean> tableFilter) {
      this.tableFilter = tableFilter;
    }

    public CommandWraper cmd(){
      return mNextWraper;
    }

    @Override public Cursor run() {
      if (transactions.get() != null) {
        throw new IllegalStateException("Cannot execute observable query in a transaction.");
      }

      final long startNanos = nanoTime();
      String where = mNextWraper.whereClause;
      String[] args = mNextWraper.whereArgs;
      String sql = mNextWraper.sql;
      String table = mNextWraper.table;
      Cursor cursor = mNextWraper.result;

      if(cursor == null) return null;

      switch (mNextWraper.cmd){
        case DELETE:
          break;
        case INSERT:
          break;
        case UPDATE:

          MatrixCursor result = null;
          try {
            final int length = cursor.getCount();
            int i = 0;
            StringBuilder sb = new StringBuilder();
            sb.append("rowid in (");
            for(cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()){
              int rowid = cursor.getInt(0);
              sb.append(rowid);
              if(i != length-1){
                sb.append(",");
              }
              i++;
            }
            sb.append(")");
            result = queryWraper(table, sb.toString());

            if (logging) {
              sql = "SELECT * FROM WHERE " + sb;
            }
          }finally {
            if(cursor != null){
              cursor.close();
            }
            cursor = mNextWraper.result = result;
          }
          break;
        case UNKNOWN:
        default:
          break;
      }

      if (logging) {
        long tookMillis = NANOSECONDS.toMillis(nanoTime() - startNanos);
        log("%s.QUERY (%sms)\n  tables: %s\n  sql: %s\n  args: %s", mNextWraper.cmd.name(), tookMillis, tableFilter,
                indentSql(sql), Arrays.toString(args));
      }

      return cursor;
    }

    @Override public String toString() {
      return mNextWraper.toString();
    }

    @Override public Query call(Set<CommandWraper> wraper) {
      this.mNextWraper = wraper.iterator().next();
      return this;
    }
  }

  public static class CommandWraper {
    public final String table;

    public String sql;

    public Command cmd;

    public String whereClause;

    public String[] whereArgs;

    Cursor result;

    public CommandWraper(Command cmd, String table, String whereClause, String ...whereArgs) {
      this(cmd, table, null, whereClause, whereArgs);
    }

    public CommandWraper(Command cmd, String table, String sql, String whereClause, String ...whereArgs) {
      this.cmd = cmd;
      this.table = table;
      this.sql = sql;
      this.whereClause = whereClause;
      this.whereArgs = whereArgs;
    }

    @Override
    public String toString() {
      return "NextWraper{" +
              "table='" + table + '\'' +
              ", sql='" + sql + '\'' +
              ", cmd=" + cmd +
              ", whereClause='" + whereClause + '\'' +
              ", whereArgs=" + Arrays.toString(whereArgs) +
              '}';
    }

    public static CommandWraper unknown(String table){
      final CommandWraper next = new CommandWraper(Command.UNKNOWN, table, null);
      return next;
    }

    public static CommandWraper insert(String table){
      final CommandWraper next = new CommandWraper(Command.INSERT, table, null);
      return next;
    }

    public static CommandWraper delete(String table, String whereClause, String ...whereArgs){
      final CommandWraper next = new CommandWraper(Command.DELETE, table, whereClause, whereArgs);
      return next;
    }

    public static CommandWraper update(String table, String whereClause, String ...whereArgs){
      final CommandWraper next = new CommandWraper(Command.UPDATE, table, whereClause, whereArgs);
      return next;
    }
  }

  public enum Command {
    INSERT, DELETE, UPDATE, QUERY, UNKNOWN
  }

  public class TableMapper extends HashMap<String, String>{

    final HashMap<String, Integer> counts = new HashMap<>();

    @Override
    public String remove(Object key) {
      synchronized (this){
        if(counts.containsKey(key)){
          counts.put((String)key, counts.get(key) - 1);
          int count = counts.get(key);
          if(count == 0){
            counts.remove(key);
            return super.remove(key);
          }
        }
        return null;
      }
    }

    @Override
    public String put(String key, String value) {
      synchronized (this){
        if(counts.containsKey(key)){
          counts.put(key, counts.get(key) + 1);
        }else{
          counts.put(key, 1);
        }
        return super.put(key, value);
      }
    }

    @Override
    public void putAll(Map<? extends String, ? extends String> map) {
      throw new RuntimeException("Not support");
    }
  }
}

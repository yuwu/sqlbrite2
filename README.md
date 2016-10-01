SQLBrite
========

A lightweight wrapper around `SQLiteOpenHelper` and `ContentResolver` which introduces reactive
stream semantics to queries.



Usage
-----

Create a `SqlBrite` instance which is an adapter for the library functionality.

```java
SqlBrite sqlBrite = SqlBrite.create();
```

Pass a `SQLiteOpenHelper` instance and a `Scheduler` to create a `BriteDatabase`.

```java
BriteDatabase db = sqlBrite.wrapDatabaseHelper(openHelper, Schedulers.io());
```

A `Scheduler` is required for a few reasons, but the most important is that query notifications can
trigger on the thread of your choice. The query can then be run without blocking the main thread or
the thread which caused the trigger.

The `BriteDatabase.createQuery` method is similar to `SQLiteDatabase.rawQuery` except it takes an
additional parameter of table(s) on which to listen for changes. Subscribe to the returned
`Observable<Query>` which will immediately notify with a `Query` to run.

```java
Observable<Query> users = db.createQuery("users", "SELECT * FROM users");
users.subscribe(new Action1<Query>() {
  @Override public void call(Query query) {
    Cursor cursor = query.run();
    // TODO parse data...
  }
});
```

Unlike a traditional `rawQuery`, updates to the specified table(s) will trigger additional
notifications for as long as you remain subscribed to the observable. This means that when you
insert, update, or delete data, any subscribed queries will update with the new data instantly.

```java
final AtomicInteger queries = new AtomicInteger();
users.subscribe(new Action1<Query>() {
  @Override public void call(Query query) {
    queries.getAndIncrement();
  }
});
System.out.println("Queries: " + queries.get()); // Prints 1

db.insert("users", createUser("jw", "Jake Wharton"));
db.insert("users", createUser("mattp", "Matt Precious"));
db.insert("users", createUser("strong", "Alec Strong"));

System.out.println("Queries: " + queries.get()); // Prints 4
```

In the previous example we re-used the `BriteDatabase` object "db" for inserts. All insert, update,
or delete operations must go through this object in order to correctly notify subscribers.

Unsubscribe from the returned `Subscription` to stop getting updates.

```java
final AtomicInteger queries = new AtomicInteger();
Subscription s = users.subscribe(new Action1<Query>() {
  @Override public void call(Query query) {
    queries.getAndIncrement();
  }
});
System.out.println("Queries: " + queries.get()); // Prints 1

db.insert("users", createUser("jw", "Jake Wharton"));
db.insert("users", createUser("mattp", "Matt Precious"));
s.unsubscribe();

db.insert("users", createUser("strong", "Alec Strong"));

System.out.println("Queries: " + queries.get()); // Prints 3
```

Use transactions to prevent large changes to the data from spamming your subscribers.

```java
final AtomicInteger queries = new AtomicInteger();
users.subscribe(new Action1<Query>() {
  @Override public void call(Query query) {
    queries.getAndIncrement();
  }
});
System.out.println("Queries: " + queries.get()); // Prints 1

Transaction transaction = db.newTransaction();
try {
  db.insert("users", createUser("jw", "Jake Wharton"));
  db.insert("users", createUser("mattp", "Matt Precious"));
  db.insert("users", createUser("strong", "Alec Strong"));
  transaction.markSuccessful();
} finally {
  transaction.end();
}

System.out.println("Queries: " + queries.get()); // Prints 2
```
*Note: You can also use try-with-resources with a `Transaction` instance.*
SQLBrite2
========

`sqlbrite2`基于[sqlbrite](https://github.com/square/sqlbrite)，取名`sqlbrite2`是为了跟sqlbrite作区分。

源码[github](https://github.com/yuwu/sqlbrite2)

在sqlbrite基础上增加了如下功能
-----

增强表变化监听
增加数据监听
删除数据监听



SQLBrite2如何使用
-----
`SQLBrite`使用教程请参考 [SQLBrite简介](https://github.com/square/sqlbrite) ，这里只给出SQLBrite2新增功能如何使用

前期准备工作，创建一个SqlBrite实例

```java
SqlBrite sqlBrite = SqlBrite.create();
BriteDatabase db = sqlBrite.wrapDatabaseHelper(openHelper, Schedulers.io());
```

表监听使用方法

```java
// 监听users表
Observable<Query> users = db.listener("users");
users.subscribe(new Action1<Query>() {
  @Override public void call(Query query) {
    Cursor cursor = query.run();
    // TODO parse data...
  }
});
```

```java
//监听users表字段id值为2的数据. id必须为主键
...
Observable<Query> users = db.listener("users", "id", 2);
...
```

```java
//1.如果users表有主键且为integer类型，这里监听的是users表主键值为2的数据
//2.如果users没有主键，这里监听的是users表中第2行的数据。
...
Observable<Query> users = db.listener("users", 2);
...
```

如果只想监听某一中操作（比如users表数据更新操作）可以使用如下事例
```java
...
// 这里监听users表中的所有更新操作
Observable<Query> users = db.listener("users", BriteDatabase.Command.UPDATE);
users.subscribe(new Action1<Query>() {
  @Override public void call(Query query) {
    // cursor表示users表中已经更新后的所有数据
    Cursor cursor = query.run();
    // TODO parse data...
  }
});
...
```

```java
// 这里监听users表字段id值为2的更新操作. id必须为主键
Observable<Query> users = db.listener("users", BriteDatabase.Command.UPDATE, "id", 2);
...
```
```java
// 同上面的效果一样。不同的地方是如果users表有主键且为integer类型，监听的是users表主键值为2的数据。如果没有主键监听的是users表的第二行数据
Observable<Query> users = db.listener("users", BriteDatabase.Command.UPDATE, 2);
...
```

```java
// 监听users表删除操作，
Observable<Query> users = db.listener("users", BriteDatabase.Command.DELETE);
users.subscribe(new Action1<Query>() {
  @Override public void call(Query query) {
    // cursor表示users表中已经删除后的数据
    Cursor cursor = query.run();
    // TODO parse data...
  }
});
...
```
```java
// 监听users表字段id值为2的删除操作
Observable<Query> users = db.listener("users", BriteDatabase.Command.DELETE, "id", 2);
users.subscribe(new Action1<Query>() {
  @Override public void call(Query query) {
    // cursor表示users表中已经删除后的数据
    Cursor cursor = query.run();
    // TODO parse data...
  }
});
...
```

```java
// 监听users表插入数据操作
Observable<Query> users = db.listener("users", BriteDatabase.Command.INSERT);
users.subscribe(new Action1<Query>() {
  @Override public void call(Query query) {
    // cursor表示users表中刚插入的数据
    Cursor cursor = query.run();
    // TODO parse data...
  }
});
...
```
[SQLBrite2](https://github.com/yuwu/sqlbrite2) 

Since queries are just regular RxJava `Observable` objects, operators can also be used to
control the frequency of notifications to subscribers.

```java
users.debounce(500, MILLISECONDS).subscribe(new Action1<Query>() {
  @Override public void call(Query query) {
    // TODO...
  }
});
```

The `SqlBrite` object can also wrap a `ContentResolver` for observing a query on another app's
content provider.

```java
BriteContentResolver resolver = sqlBrite.wrapContentProvider(contentResolver, Schedulers.io());
Observable<Query> query = resolver.createQuery(/*...*/);
```

The full power of RxJava's operators are available for combining, filtering, and triggering any
number of queries and data changes.



Philosophy
----------

SqlBrite's only responsibility is to be a mechanism for coordinating and composing the notification
of updates to tables such that you can update queries as soon as data changes.

This library is not an ORM. It is not a type-safe query mechanism. It won't serialize the same POJOs
you use for Gson. It's not going to perform database migrations for you.

Some of these features are offered by [SQLDelight][sqldelight] which can be used with SQLBrite.



Download
--------

```groovy
compile 'com.squareup.sqlbrite:sqlbrite:0.7.0'
```

Snapshots of the development version are available in [Sonatype's `snapshots` repository][snap].



License
-------

    Copyright 2015 Square, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.





 [snap]: https://oss.sonatype.org/content/repositories/snapshots/
 [sqldelight]: https://github.com/square/sqldelight/

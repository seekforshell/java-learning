# InnoDB存储引擎



## 优点

1.DML操作支持事务，遵循ACID特性

2.行级锁和一致性读保证了多用户的并发和性能

3.可以保证数据一致性。支持外键约束

## 特性

ACID

## 事务日志





## 索引

包括B-tree索引、Hash索引和全文索引。

B-tree索引可以分为主键索引和非主键索引。主键索引也称为聚集索引。普通索引存储的是主键索引的id，主键索引的叶子节点存储的数据块。

通过普通索引查询数据库时会出现回表的问题。

Hash索引通过合理使用可以极大改善数据库性能。

全文索引支持like这种查询。

## MVCC



**Table 14.1 InnoDB Storage Engine Features**

| Feature                                                      | Support                                                      |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| **B-tree indexes**                                           | Yes                                                          |
| **Backup/point-in-time recovery** (Implemented in the server, rather than in the storage engine.) | Yes                                                          |
| **Cluster database support**                                 | No                                                           |
| **Clustered indexes**                                        | Yes                                                          |
| **Compressed data**                                          | Yes                                                          |
| **Data caches**                                              | Yes                                                          |
| **Encrypted data**                                           | Yes (Implemented in the server via encryption functions; In MySQL 5.7 and later, data-at-rest tablespace encryption is supported.) |
| **Foreign key support**                                      | Yes                                                          |
| **Full-text search indexes**                                 | Yes (InnoDB support for FULLTEXT indexes is available in MySQL 5.6 and later.) |
| **Geospatial data type support**                             | Yes                                                          |
| **Geospatial indexing support**                              | Yes (InnoDB support for geospatial indexing is available in MySQL 5.7 and later.) |
| **Hash indexes**                                             | No (InnoDB utilizes hash indexes internally for its Adaptive Hash Index feature.) |
| **Index caches**                                             | Yes                                                          |
| **Locking granularity**                                      | Row                                                          |
| **MVCC**                                                     | Yes                                                          |
| **Replication support** (Implemented in the server, rather than in the storage engine.) | Yes                                                          |
| **Storage limits**                                           | 64TB                                                         |
| **T-tree indexes**                                           | No                                                           |
| **Transactions**                                             | Yes                                                          |
| **Update statistics for data dictionary**                    | Yes                                                          |

## 架构



![innodb-architecture](images/innodb-architecture.png)



### 磁盘数据结构（On-Disk Structures）



```shell
# 展示了mysql的数据存储格式
# 1. redolog默认存储在ib_logfile0和ib_logfile1中，可以通过show variables like 'innodb_log_files_in_group'
# 进行相应的配置
# 2. undolog存放在
# ls -l /usr/local/mysql/data
total 2079472
drwxr-x---  209 _mysql  _mysql       6688 Dec 31  2019 ambari
-rw-r-----    1 _mysql  _mysql         56 Sep 12  2019 auto.cnf
-rw-r-----    1 _mysql  _mysql        557 Jul 13 11:47 ib_buffer_pool
-rw-r-----    1 _mysql  _mysql   50331648 Jul 28 13:45 ib_logfile0   # redo log
-rw-r-----    1 _mysql  _mysql   50331648 Jul 28 13:39 ib_logfile1   # redo log
-rw-r-----    1 _mysql  _mysql   79691776 Jul 28 13:45 ibdata1       # 系统表空间
-rw-r-----    1 _mysql  _mysql   12582912 Jul 28 13:54 ibtmp1
drwxr-x---   11 _mysql  _mysql        352 Jul 28 10:45 lab           # u
-rw-r-----    1 _mysql  _mysql      16438 Sep 12  2019 localhost.err
drwxr-x---   77 _mysql  _mysql       2464 Sep 12  2019 mysql
-rw-r-----    1 _mysql  _mysql        177 Jul 13 10:26 mysql-bin.000001
-rw-r-----    1 _mysql  _mysql       2427 Jul 13 11:47 mysql-bin.000002
-rw-r-----    1 _mysql  _mysql   44458870 Jul 16 10:15 mysql-bin.000003
-rw-r-----    1 _mysql  _mysql  818633484 Jul 28 13:45 mysql-bin.000004
-rw-r-----    1 _mysql  _mysql        154 Jul 28 13:45 mysql-bin.000005
-rw-r-----    1 _mysql  _mysql         95 Jul 28 13:45 mysql-bin.index
-rw-r-----    1 _mysql  _mysql    1095940 Jul 28 18:42 mysqld.local.err
-rw-r-----    1 _mysql  _mysql          3 Jul 28 13:45 mysqld.local.pid
drwxr-x---   90 _mysql  _mysql       2880 Sep 12  2019 performance_schema
-rw-r-----    1 _mysql  _mysql     333587 Jul 13 09:53 renfeideMacBook-Air.local.err
-rw-r-----    1 _mysql  _mysql          6 Jul 13 09:53 renfeideMacBook-Air.local.pid
drwxr-x---  108 _mysql  _mysql       3456 Sep 12  2019 sys
# file-per-table
~ root# ls -l /usr/local/mysql/data/lab/
total 371680
-rw-r-----  1 _mysql  _mysql       8664 Jul 16 09:52 consumer.frm
-rw-r-----  1 _mysql  _mysql     163840 Jul 16 09:57 consumer.ibd
-rw-r-----  1 _mysql  _mysql         60 Jul 13 09:47 db.opt
-rw-r-----  1 _mysql  _mysql       8650 Jul 28 10:45 goods.frm
-rw-r-----  1 _mysql  _mysql  188743680 Jul 28 13:45 goods.ibd
-rw-r-----  1 _mysql  _mysql       8680 Jul 13 10:34 order.frm
-rw-r-----  1 _mysql  _mysql      98304 Jul 13 10:34 order.ibd
-rw-r-----  1 _mysql  _mysql       8706 Jul 13 10:40 stock.frm
-rw-r-----  1 _mysql  _mysql      98304 Jul 13 10:41 stock.ibd
```

表的存储方式根据innodb_file_per_table这个变量是否开启把表的数据存储到不同的位置；如果开启则存储在File-Per-Table的Tablespaces区域；如果禁用咋放到System Tablespace中。



那一个表一个文件的存储方式相对于其他的存储方式有什么有缺点呢？



#### Redo Log

##### **作用**

redolog的作用是当执行的操作由于系统崩溃或者down机等异常原因时用来重放未完成的动作。



##### 参考



https://www.cnblogs.com/hapjin/archive/2019/09/28/11521506.html

#### Undo Log




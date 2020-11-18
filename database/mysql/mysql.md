# MySQL修炼手册

## InnoDB存储引擎



### 优点

1.DML操作支持事务，遵循ACID特性

2.行级锁和一致性读保证了多用户的并发和性能

3.可以保证数据一致性。支持外键约束

### 特性

### 事务



### MVCC



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

### 架构



![innodb-architecture](/Users/renfei/workspace/git/java-learning/database/mysql/images/innodb-architecture.png)



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



### Redo Log

##### **作用**

redolog的作用是当执行的操作由于系统崩溃或者down机等异常原因时用来重放未完成的动作。



##### 参考



https://www.cnblogs.com/hapjin/archive/2019/09/28/11521506.html

### Undo Log





## 索引优化



### 索引分类

常见的索引类型有：聚集（主键）索引、二级索引。所有非聚集索引之外的索引都被成为二级索引。

二级索引常见的包括：



#### 聚集索引

聚集索引选择的三种情况：

- When you define a `PRIMARY KEY` on your table, `InnoDB` uses it as the clustered index. Define a primary key for each table that you create. If there is no logical unique and non-null column or set of columns, add a new [auto-increment](https://dev.mysql.com/doc/refman/5.7/en/glossary.html#glos_auto_increment) column, whose values are filled in automatically.
- If you do not define a `PRIMARY KEY` for your table, MySQL locates the first `UNIQUE` index where all the key columns are `NOT NULL` and `InnoDB` uses it as the clustered index.
- If the table has no `PRIMARY KEY` or suitable `UNIQUE` index, `InnoDB` internally generates a hidden clustered index named `GEN_CLUST_INDEX` on a synthetic column containing row ID values. The rows are ordered by the ID that `InnoDB` assigns to the rows in such a table. The row ID is a 6-byte field that increases monotonically as new rows are inserted. Thus, the rows ordered by the row ID are physically in insertion order.

#### 二级索引

二级索引创建了表的字段子集，适合：比如你只需要表中的某个或者某几个字段，否则在更复杂的查询时需要进行回表操作



### 索引优化

#### 索引常用命令

#### explain

##### explain 命令可以查询sql语句的执行计划。查找性能瓶颈

explain的输出列如下所示：

| Column                                                       | JSON Name       | Meaning                                        |
| :----------------------------------------------------------- | :-------------- | :--------------------------------------------- |
| [`id`](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html#explain_id) | `select_id`     | The `SELECT` identifier                        |
| [`select_type`](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html#explain_select_type) | None            | The `SELECT` type                              |
| [`table`](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html#explain_table) | `table_name`    | The table for the output row                   |
| [`partitions`](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html#explain_partitions) | `partitions`    | The matching partitions                        |
| [`type`](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html#explain_type) | `access_type`   | The join type: 常用于查询优化                  |
| [`possible_keys`](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html#explain_possible_keys) | `possible_keys` | The possible indexes to choose                 |
| [`key`](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html#explain_key) | `key`           | The index actually chosen                      |
| [`key_len`](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html#explain_key_len) | `key_length`    | The length of the chosen key                   |
| [`ref`](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html#explain_ref) | `ref`           | The columns compared to the index              |
| [`rows`](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html#explain_rows) | `rows`          | Estimate of rows to be examined                |
| [`filtered`](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html#explain_filtered) | `filtered`      | Percentage of rows filtered by table condition |
| [`Extra`](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html#explain_extra) | None            | Additional information                         |



示例：



```json
{
	"query_block" : {
		"cost_info" : {
			"query_cost" : "3.80"
		},
		"nested_loop" : [
			{
				"table" : {
					"access_type" : "index",
					"cost_info" : {
						"data_read_per_join" : "3K",
						"eval_cost" : "0.40",
						"prefix_cost" : "1.40",
						"read_cost" : "1.00"
					},
					"filtered" : "100.00",
					"key" : "fk_cluster_id_idx",
					"key_length" : "98",
					"possible_keys" : [
						"fk_cluster_id_idx"
					],
					"rows_examined_per_scan" : 2,
					"rows_produced_per_join" : 2,
					"table_name" : "t1",
					"used_columns" : [
						"cluster_id"
					],
					"used_key_parts" : [
						"cluster_id"
					],
					"using_index" : true
				}
			},
			{
				"table" : {
					"access_type" : "eq_ref",
					"cost_info" : {
						"data_read_per_join" : "3K",
						"eval_cost" : "0.40",
						"prefix_cost" : "3.80",
						"read_cost" : "2.00"
					},
					"filtered" : "100.00",
					"key" : "cluster_id_UNIQUE",
					"key_length" : "98",
					"possible_keys" : [
						"cluster_id_UNIQUE"
					],
					"ref" : [
						"update_resource.t1.cluster_id"
					],
					"rows_examined_per_scan" : 1,
					"rows_produced_per_join" : 2,
					"table_name" : "t2",
					"used_columns" : [
						"cluster_id"
					],
					"used_key_parts" : [
						"cluster_id"
					],
					"using_index" : true
				}
			}
		],
		"select_id" : 1
	}
}
```

Type:





##### 参考

https://dev.mysql.com/doc/refman/8.0/en/explain-output.html

https://blog.csdn.net/weixin_41558728/article/details/81704916

#### 查询耗时时间

```shell
show variables like '%profiling%';
# 开启审计
set profiling=ON;
show variables;

# 查询诊断信息
show profiles;

```



<img src="images/mysql-profile.png" alt="image-20201016142520408" style="zoom:50%;" />

#### 查询索引空间大小

```
SELECT TABLE_NAME AS '表名',
CONCAT(ROUND(TABLE_ROWS/10000, 2), ' 万行') AS '行数',
CONCAT(ROUND(DATA_LENGTH/(1024*1024*1024), 2), ' GB') AS '表空间',
CONCAT(ROUND(INDEX_LENGTH/(1024*1024*1024), 2), ' GB') AS '索引空间',
CONCAT(ROUND((DATA_LENGTH+INDEX_LENGTH)/(1024*1024*1024),2),' GB') AS'总空间'
FROM information_schema.TABLES 
```

#### 查看缓存信息

```
# 是否打开缓存
show variables like 'have_query_cache'
# 使用此语句可以查看缓存的开关、大小等信息
show variables like 'query';　

# 当前缓存的情况
show status like '%Qcache%';

# 清理缓存
 flush query cache;
 reset query cache
```



### 索引测试







## 参考资料

Explain:	

https://dev.mysql.com/doc/refman/5.7/en/explain-output.html

MySQL执行计划

https://www.cnblogs.com/sunjingwu/p/10755823.html

## 数据库命令



### mysqldump

   ·   --no-create-info, -t

       Do not write CREATE TABLE statements that create each dumped table.
    
           Note
           This option does not exclude statements creating log file groups or tablespaces from mysqldump output; however, you can use the
           --no-tablespaces option for this purpose.
       
    ·   --no-data, -d
    
       Do not write any table row information (that is, do not dump table contents). This is useful if you want to dump only the CREATE
       TABLE statement for the table (for example, to create an empty copy of the table by loading the dump file).


​    
​    ·   --tables
​    
​       Override the --databases or -B option.  mysqldump regards all name arguments following the option as table names.
​    
​    When you selectively enable or disable the effect of a group option, order is important because options are processed first to last. For
​    example, --disable-keys --lock-tables --skip-opt would not have the intended effect; it is the same as --skip-opt by itself.
​    Examples.PP To make a backup of an entire database:
​    
​       shell> mysqldump db_name > backup-file.sql
​    
​    To load the dump file back into the server:
​    
       shell> mysql db_name < backup-file.sql
    
    Another way to reload the dump file:
    
       shell> mysql -e "source /path-to-backup/backup-file.sql" db_name
    
    mysqldump is also very useful for populating databases by copying data from one MySQL server to another:
    
       shell> mysqldump --opt db_name | mysql --host=remote_host -C db_name
    
    You can dump several databases with one command:
    
       shell> mysqldump --databases db_name1 [db_name2 ...] > my_databases.sql
    
    To dump all databases, use the --all-databases option:
    
       shell> mysqldump --all-databases > all_databases.sql
    
    For InnoDB tables, mysqldump provides a way of making an online backup:
    
       shell> mysqldump --all-databases --master-data --single-transaction > all_databases.sql

dump数据库的所有表级库的结构信息，不包含数据
mysqldump -d db_name -uuser -ppassword > minder.sql

dump数据库的所有表级库的结构信息，包含数据
mysqldump -t db_name -uuser -ppassword > minder.sql

## SQL语句

### 函数

#### 窗口函数



| Name                                                         | Description                                                  |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| [`CUME_DIST()`](https://dev.mysql.com/doc/refman/8.0/en/window-function-descriptions.html#function_cume-dist) | Cumulative distribution value                                |
| [`DENSE_RANK()`](https://dev.mysql.com/doc/refman/8.0/en/window-function-descriptions.html#function_dense-rank) | Rank of current row within its partition, without gaps       |
| [`FIRST_VALUE()`](https://dev.mysql.com/doc/refman/8.0/en/window-function-descriptions.html#function_first-value) | Value of argument from first row of window frame             |
| [`LAG()`](https://dev.mysql.com/doc/refman/8.0/en/window-function-descriptions.html#function_lag) | Value of argument from row lagging current row within partition |
| [`LAST_VALUE()`](https://dev.mysql.com/doc/refman/8.0/en/window-function-descriptions.html#function_last-value) | Value of argument from last row of window frame              |
| [`LEAD()`](https://dev.mysql.com/doc/refman/8.0/en/window-function-descriptions.html#function_lead) | Value of argument from row leading current row within partition |
| [`NTH_VALUE()`](https://dev.mysql.com/doc/refman/8.0/en/window-function-descriptions.html#function_nth-value) | Value of argument from N-th row of window frame              |
| [`NTILE()`](https://dev.mysql.com/doc/refman/8.0/en/window-function-descriptions.html#function_ntile) | Bucket number of current row within its partition.           |
| [`PERCENT_RANK()`](https://dev.mysql.com/doc/refman/8.0/en/window-function-descriptions.html#function_percent-rank) | Percentage rank value                                        |
| [`RANK()`](https://dev.mysql.com/doc/refman/8.0/en/window-function-descriptions.html#function_rank) | Rank of current row within its partition, with gaps          |
| [`ROW_NUMBER()`](https://dev.mysql.com/doc/refman/8.0/en/window-function-descriptions.html#function_row-number) | Number of current row within its partition                   |



参考手册：

https://www.jianshu.com/p/aabd8bf6b51c

#### 聚合函数



#### 常用函数

##### IFNULL

注意IFNULL函数第一个参数表示一个表达式，不能直接放置一个字段，如果将一个字段放到这个位置，mysql并不会提示有问题，会造成异常不到的结果

```
IFNULL(expression, alt_value)
```

### 存储过程



```
DROP PROCEDURE IF EXISTS proc_initData;

DELIMITER $

CREATE PROCEDURE proc_initData()

BEGIN

DECLARE i INT DEFAULT 1;

WHILE i<=1000 DO

INSERT INTO consumer(username,password,email) VALUES(concat('liuyiyi@', i), '123456', concat(i, '@qq.com'));

SET i = i+1;

END WHILE;

END $

CALL proc_initData();
```



### DDL操作

#### 修改列



```
alter table consumer change column id id BIGINT(20)
```



#### 增加列

```

```



#### 添加外键

```
ALTER TABLE `lab`.`order` 
ADD CONSTRAINT `order_good`
  FOREIGN KEY (`id`)
  REFERENCES `lab`.`goods` (`id`)
  ON DELETE NO ACTION
  ON UPDATE CASCADE;
```

### DML操作

#### 查询-select



#### 更新-update



####  插入-insert

#### 如果不存在则插入

```sql
INSERT INTO goods (gname,price,descr) select 'aa',10,'desc' from dual where not exists (select * from goods where gname = 'aa')
```

注意 insert 多个值得时候后面是以括号分隔的。

```sql
INSERT INTO t1 (c1,c2) VALUES (1,'a'), (NULL,'b'), (5,'c'), (NULL,'d');
```

### DCL操作

Data Control Language


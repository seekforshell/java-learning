# MySQL修炼手册

## 数据库命令

## 函数

### 窗口函数



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

### 聚合函数



## mysqldump

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
       shell> mysqldump db_name > backup-file.sql
    
    To load the dump file back into the server:
    
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

## 存储过程



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



## DDL操作

### 修改列



```
alter table consumer change column id id BIGINT(20)
```



### 增加列

```

```



### 添加外键

```
ALTER TABLE `lab`.`order` 
ADD CONSTRAINT `order_good`
  FOREIGN KEY (`id`)
  REFERENCES `lab`.`goods` (`id`)
  ON DELETE NO ACTION
  ON UPDATE CASCADE;
```

## DML操作

### 查询-select



### 更新-update



###  插入-insert

#### 如果不存在则插入

```sql
INSERT INTO goods (gname,price,descr) select 'aa',10,'desc' from dual where not exists (select * from goods where gname = 'aa')
```

注意 insert 多个值得时候后面是以括号分隔的。

```sql
INSERT INTO t1 (c1,c2) VALUES (1,'a'), (NULL,'b'), (5,'c'), (NULL,'d');
```

## DCL操作

Data Control Language


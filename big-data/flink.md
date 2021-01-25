



参考文档：



https://www.jianshu.com/p/4d31d6cddc99



# Connector



## 参考

### postgres用法

```
[gpadmin@99a4177b64d2 greenplum-db]$ ./bin/psql -U gpadmin smoke
psql (8.2.15)
Type "help" for help.

smoke=# create table ticktok_user (user_id varchar, user_name varchar);NOTICE:  Table doesn't have 'DISTRIBUTED BY' clause -- Using column named 'user_id' as the Greenplum Database data distribution key for this table.
HINT:  The 'DISTRIBUTED BY' clause determines the distribution of data. Make sure column(s) chosen are the optimal data distribution key to minimize skew.
CREATE TABLE
smoke=# insert into userinfo values('fans_001', 'xiaoming');
ERROR:  invalid input syntax for integer: "fans_001"
smoke=# insert into ticktok_user values('fans_001', 'xiaoming');
INSERT 0 1
smoke=# insert into ticktok_user values('fans_002', 'xiaoming002');
INSERT 0 1
smoke=# insert into ticktok_user values('fans_003', 'xiaoming003');
INSERT 0 1
smoke=# 
```

### Kudu用法

```

[cdh.dtwave.sit.local:21000] > create database db_smoke;
Query: create database db_smoke
Fetched 0 row(s) in 0.36s
[cdh.dtwave.sit.local:21000] > use db_smoke;
Query: use db_smoke
[cdh.dtwave.sit.local:21000] > create table tiktok_gift_side (gift_id varchar,gift_price varchar);
Query: create table tiktok_gift_side (gift_id varchar,gift_price varchar)
Fetched 0 row(s) in 0.29s
[cdh.dtwave.sit.local:21000] > 
```



# Flink任务实例

## 抖音排行数据



```sql
use catalog default_catalog;
set 'table.sql-dialect'='default';

-- 抖音直播
DROP table if exists ticktok_live;
CREATE TABLE ticktok_live (
    `live_id` STRING,  -- 直播Id
    `blogger_id` STRING,  -- 博主Id
    `live_time` TIMESTAMP(3),  -- 直播时间
     WATERMARK FOR live_time AS live_time - INTERVAL '5' SECOND,
    ts as PROCTIME()
) WITH (
    'connector' = 'kafka-0.11',
    'topic' = 'ticktok_live',
    'properties.bootstrap.servers' = '192.168.90.71:9092,192.168.90.119:9092,192.168.90.120:9092',
    'properties.group.id' = 'flink11',
    'format' = 'json',
    'scan.startup.mode' = 'latest-offset'
);

-- 抖音直播打赏
DROP table if exists ticktok_reword;
CREATE TABLE ticktok_reword (
    `live_id` STRING,  -- 直播Id
    `fans_id` STRING,  -- 粉丝Id
    `reward_gift_id` STRING, -- 礼物类型
    `reward_gift_num` INTEGER,  -- 礼物数量
    `reward_time` TIMESTAMP(3),  -- 直播时间
    ts as PROCTIME()
) WITH (
    'connector' = 'kafka-0.11',
    'topic' = 'ticktok_reword',   
    'properties.bootstrap.servers' = '192.168.90.71:9092,192.168.90.119:9092,192.168.90.120:9092',
    'properties.group.id' = 'flink11',
    'format' = 'json',
    'scan.startup.mode' = 'latest-offset'
);

-- 抖音礼物详情，存放于redis   
DROP table if exists ticktok_gift_side;
CREATE TABLE ticktok_gift_side (
    `gift_id` STRING,  -- 礼物id
    `gift_price` STRING -- 礼物价格信息
) WITH (
    'connector.type' = 'redis',
    'redis-mode' = 'standalone',
    'redis.ip' = '192.168.90.40',
    'redis.port' = '6379',
    'command' = 'SET'
);

-- 抖音用户详情，存放于hbase
DROP table if exists ticktok_user;
CREATE TABLE ticktok_user(
     `user_id` string,
     `user` ROW<`name` string>,
      PRIMARY KEY(user_id) NOT ENFORCED 
    )WITH(
        'connector'='hbase-1.4',
         'zookeeper.quorum' = 'cdh.dtwave.sit.local,cdh-dev-node-119,cdh-dev-node-120',
         'zookeeper.znode.parent' = '/hbase',
         'table-name' = 'ticktok_user' 
);

-- sink 抖音博主收入排名
create CATALOG hive_catalog with (
    'type' = 'hive',
    'hive-conf-dir' = '/etc/hadoop/conf',
    'default-database' = 'redis_connector'
);
use CATALOG hive_catalog;
set 'table.sql-dialect' = 'hive';
DROP TABLE IF EXISTS redis_connector.ticktok_live_income_top_sink;
CREATE TABLE redis_connector.ticktok_live_income_top_sink (
    `blogger_id` string,
    `blogger_name` string,
    `live_id` string,
    `live_income` BIGINT,
    `calc_time` TIMESTAMP,
    `fans_num` BIGINT 
    ) 
    STORED AS PARQUET 
    TBLPROPERTIES (
        'parquet.compression'='SNAPPY',
        'sink.partition-commit.policy.kind' = 'metastore,success-file',
        'sink.partition-commit.success-file.name' = '_SUCCESS'
);

use catalog default_catalog;
set 'table.sql-dialect'='default';

insert into
    hive_catalog.redis_connector.ticktok_live_income_top_sink
SELECT
    t4.user_id,
    t4.`user`.`name`,
    t1.live_id,
    sum(t2.reward_gift_num * 10) as total_income, -- 总收入
    CURRENT_TIMESTAMP,    -- 统计时间
    cast(RAND_INTEGER(10, 100)  as BIGINT) -- mock粉丝数
FROM
    ticktok_live as t1
inner join ticktok_reword t2 on t1.live_id = t2.live_id
inner join ticktok_gift_side for system_time as of t1.ts as t3 
	on t2.reward_gift_id = t3.gift_id
inner join ticktok_user for system_time as of t1.ts as t4 
    on t1.blogger_id = t4.user_id
group by HOP (t1.ts, INTERVAL '5' SECOND, INTERVAL '1' MINUTE), t4.user_id, t4.`user`.`name`, t1.live_id;
    
```


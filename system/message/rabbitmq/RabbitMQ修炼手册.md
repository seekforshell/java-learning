

# RabbitMQ修炼手册

## 安装

### 集群安装步骤

#### 环境准备

##### **安装Erlang**

**1.安装erlang的依赖工具**

```

yum install -y gcc gcc-c++ unixODBC-devel openssl-devel ncurses-deve perl

```

**2.下载、编译、安装**

```shell
wget http://erlang.org/download/otp_src_23.0.tar.gz
tar xvf otp_src_23.0.tar.gz
cd otp_src_23.0

mkdir /usr/local/erlang
./configure --prefix=/usr/local/erlang
```

**3.配置环境变量** 

vim /etc/profile添加以下内容

```
ERL_PATH=/usr/local/erlang/bin
PATH=$ERL_PATH:$PATH
```

#### 单机安装RabbitMQ

**1.安装依赖工具包**

```
yum install -y socat
```

**2.下载、安装**

```shell
wget https://github.com/rabbitmq/rabbitmq-server/releases/download/v3.8.5/rabbitmq-server-3.8.5-1.el7.noarch.rpm

# 某些情况erlang版本需求满足时无法正常安装rabbitmq可考虑不做检查
rpm -i --force --nodeps rabbitmq-server-3.8.5-1.el7.noarch.rpm
```

#### 集群配置



```
scp /var/lib/rabbitmq/.erlang.cookie node:/var/lib/rabbitmq/

chmod 400 /var/lib/rabbitmq/.erlang.cookie

service rabbitmq-server restart

rabbitmqctl stop_app
rabbitmqctl reset
## --ram 指定内存节点类型，--disc指定磁盘节点类型
rabbitmqctl join_cluster --ram rabbit@node-2
rabbitmqctl start_app

```



#### 开启后台管理



```
rabbitmq-plugins enable rabbitmq_management
rabbitmqctl add_user admin admin
rabbitmqctl set_user_tags admin administrator
```



访问页面

```
http://192.168.126.131:15672/
```


![image-20200801211319605](resource\image-20200801211319605.png)
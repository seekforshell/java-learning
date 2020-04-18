
# Maven常用插件

## 全局变量替换

当你需要在多个脚本中，比如shell,python中引用变量时，可以使用以下插件在
编译期间进行变量替换，在运行时进行检查
http://www.mojohaus.org/build-helper-maven-plugin/

# 如何使用不同的仓库


如果是插件可以在pom文件中添加

  <pluginRepositories>
    <pluginRepository>
      <id>oss.sonatype.org</id>
      <name>OSS Sonatype Staging</name>
      <url>https://oss.sonatype.org/content/groups/staging</url>
    </pluginRepository>
  </pluginRepositories>
如果是依赖可以在pom文件中添加

  <repositories>
    <repository>
      <id>oss.sonatype.org</id>
      <name>OSS Sonatype Staging</name>
      <url>https://oss.sonatype.org/content/groups/staging</url>
    </repository>
    <repository>
      <id>spring-milestones</id>
      <name>Spring Milestones</name>
      <url>https://repo.spring.io/milestone</url>
      <snapshots>
        <enabled>false</enabled>
      </snapshots>
    </repository>
    <repository>
      <id>ASF Staging</id>
      <url>https://repository.apache.org/content/groups/staging/</url>
    </repository>
    <repository>
      <id>ASF Snapshots</id>
      <url>https://repository.apache.org/content/repositories/snapshots/</url>
      <snapshots>
        <enabled>true</enabled>
      </snapshots>
      <releases>
        <enabled>false</enabled>
      </releases>
    </repository>
    <!-- ToDo: remove after moving the Metrics HDP dependencies to the public nexus -->
    <repository>
      <id>nexus-hortonworks</id>
      <url>https://repo.hortonworks.com/content/groups/public/</url>
    </repository>
  </repositories>  
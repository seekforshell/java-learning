# Tomcat



## 架构





### 举例



这里举dubbo如何使用嵌入式tomcat来进行启动的。



```java
    public TomcatHttpServer(URL url, final HttpHandler handler) {
        super(url, handler);

        this.url = url;
      	// 添加handler
        DispatcherServlet.addHttpHandler(url.getPort(), handler);
        String baseDir = new File(System.getProperty("java.io.tmpdir")).getAbsolutePath();
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir);
        tomcat.setPort(url.getPort());
        tomcat.getConnector().setProperty(
                "maxThreads", String.valueOf(url.getParameter(Constants.THREADS_KEY, Constants.DEFAULT_THREADS)));
//        tomcat.getConnector().setProperty(
//                "minSpareThreads", String.valueOf(url.getParameter(Constants.THREADS_KEY, Constants.DEFAULT_THREADS)));

        tomcat.getConnector().setProperty(
                "maxConnections", String.valueOf(url.getParameter(Constants.ACCEPTS_KEY, -1)));

        tomcat.getConnector().setProperty("URIEncoding", "UTF-8");
        tomcat.getConnector().setProperty("connectionTimeout", "60000");

        tomcat.getConnector().setProperty("maxKeepAliveRequests", "-1");
        tomcat.getConnector().setProtocol("org.apache.coyote.http11.Http11NioProtocol");

        Context context = tomcat.addContext("/", baseDir);
      	// 自定义servlet
        Tomcat.addServlet(context, "dispatcher", new DispatcherServlet());
        context.addServletMapping("/*", "dispatcher");
        ServletManager.getInstance().addServletContext(url.getPort(), context.getServletContext());

        try {
            tomcat.start();
        } catch (LifecycleException e) {
            throw new IllegalStateException("Failed to start tomcat server at " + url.getAddress(), e);
        }
    }
```


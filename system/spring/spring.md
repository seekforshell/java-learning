[toc]



# Spring 修炼手册



## 源码学习

### Spring boot启动流程



SpringApplication初始化，spring boot会根据上下文类加载器加载spring.factories资源并将所有的接口实现存储到缓存中：

```java
	public SpringApplication(ResourceLoader resourceLoader, Class<?>... primarySources) {
		this.resourceLoader = resourceLoader;
		Assert.notNull(primarySources, "PrimarySources must not be null");
		this.primarySources = new LinkedHashSet<>(Arrays.asList(primarySources));
		this.webApplicationType = WebApplicationType.deduceFromClasspath();
		setInitializers((Collection) getSpringFactoriesInstances(ApplicationContextInitializer.class));
		setListeners((Collection) getSpringFactoriesInstances(ApplicationListener.class));
		this.mainApplicationClass = deduceMainApplicationClass();
	}
```

缓存位置：org.springframework.core.io.support.SpringFactoriesLoader

```java
	private static final Map<ClassLoader, MultiValueMap<String, String>> cache = new ConcurrentReferenceHashMap<>();
```



**org.springframework.boot.SpringApplication#run(java.lang.String...)**

context创建是根据webApplicationType来决定的

如果是servlet则其实现为

```java
org.springframework.boot."
      + "web.servlet.context.AnnotationConfigServletWebServerApplicationContext
```

```java
	/**
	 * Run the Spring application, creating and refreshing a new
	 * {@link ApplicationContext}.
	 * @param args the application arguments (usually passed from a Java main method)
	 * @return a running {@link ApplicationContext}
	 */
	public ConfigurableApplicationContext run(String... args) {
		StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		ConfigurableApplicationContext context = null;
		Collection<SpringBootExceptionReporter> exceptionReporters = new ArrayList<>();
		configureHeadlessProperty();
		SpringApplicationRunListeners listeners = getRunListeners(args);
		listeners.starting();
		try {
			ApplicationArguments applicationArguments = new DefaultApplicationArguments(args);
			ConfigurableEnvironment environment = prepareEnvironment(listeners, applicationArguments);
			configureIgnoreBeanInfo(environment);
			Banner printedBanner = printBanner(environment);
      // @1
			context = createApplicationContext();
			exceptionReporters = getSpringFactoriesInstances(SpringBootExceptionReporter.class,
					new Class[] { ConfigurableApplicationContext.class }, context);
			prepareContext(context, environment, listeners, applicationArguments, printedBanner);
			refreshContext(context);
			afterRefresh(context, applicationArguments);
			stopWatch.stop();
			if (this.logStartupInfo) {
				new StartupInfoLogger(this.mainApplicationClass).logStarted(getApplicationLog(), stopWatch);
			}
			listeners.started(context);
			callRunners(context, applicationArguments);
		}
		catch (Throwable ex) {
			handleRunFailure(context, ex, exceptionReporters, listeners);
			throw new IllegalStateException(ex);
		}

		try {
			listeners.running(context);
		}
		catch (Throwable ex) {
			handleRunFailure(context, ex, exceptionReporters, null);
			throw new IllegalStateException(ex);
		}
		return context;
	}
```



对于AnnotationConfigServletWebServerApplicationContext里有两个非常重要的字段

```
	public AnnotationConfigServletWebServerApplicationContext(DefaultListableBeanFactory beanFactory) {
		super(beanFactory);
		this.reader = new AnnotatedBeanDefinitionReader(this);
		this.scanner = new ClassPathBeanDefinitionScanner(this);
	}
```



**org.springframework.boot.SpringApplication#prepareContext#load**



```java
	/**
	 * Load beans into the application context.
	 * @param context the context to load beans into
	 * @param sources the sources to load
	 */
	protected void load(ApplicationContext context, Object[] sources) {
		if (logger.isDebugEnabled()) {
			logger.debug("Loading source " + StringUtils.arrayToCommaDelimitedString(sources));
		}
    /* 定义bean定义的加载器，后续流程会解析 */
		BeanDefinitionLoader loader = createBeanDefinitionLoader(getBeanDefinitionRegistry(context), sources);
		if (this.beanNameGenerator != null) {
			loader.setBeanNameGenerator(this.beanNameGenerator);
		}
		if (this.resourceLoader != null) {
			loader.setResourceLoader(this.resourceLoader);
		}
		if (this.environment != null) {
			loader.setEnvironment(this.environment);
		}
		loader.load();
	}
```

load流程中核心代码块如下：

```java
	/**
	 * Register a bean from the given bean class, deriving its metadata from
	 * class-declared annotations.
	 * @param beanClass the class of the bean
	 * @param name an explicit name for the bean
	 * @param qualifiers specific qualifier annotations to consider, if any,
	 * in addition to qualifiers at the bean class level
	 * @param supplier a callback for creating an instance of the bean
	 * (may be {@code null})
	 * @param customizers one or more callbacks for customizing the factory's
	 * {@link BeanDefinition}, e.g. setting a lazy-init or primary flag
	 * @since 5.0
	 */
	private <T> void doRegisterBean(Class<T> beanClass, @Nullable String name,
			@Nullable Class<? extends Annotation>[] qualifiers, @Nullable Supplier<T> supplier,
			@Nullable BeanDefinitionCustomizer[] customizers) {

		AnnotatedGenericBeanDefinition abd = new AnnotatedGenericBeanDefinition(beanClass);
		if (this.conditionEvaluator.shouldSkip(abd.getMetadata())) {
			return;
		}

		abd.setInstanceSupplier(supplier);
		ScopeMetadata scopeMetadata = this.scopeMetadataResolver.resolveScopeMetadata(abd);
		abd.setScope(scopeMetadata.getScopeName());
		String beanName = (name != null ? name : this.beanNameGenerator.generateBeanName(abd, this.registry));

    /* 处理类的注解等元数据，比如dependOn,Primary,Role等 */
		AnnotationConfigUtils.processCommonDefinitionAnnotations(abd);
		if (qualifiers != null) {
			for (Class<? extends Annotation> qualifier : qualifiers) {
				if (Primary.class == qualifier) {
					abd.setPrimary(true);
				}
				else if (Lazy.class == qualifier) {
					abd.setLazyInit(true);
				}
				else {
					abd.addQualifier(new AutowireCandidateQualifier(qualifier));
				}
			}
		}
		if (customizers != null) {
			for (BeanDefinitionCustomizer customizer : customizers) {
				customizer.customize(abd);
			}
		}

		BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(abd, beanName);
		definitionHolder = AnnotationConfigUtils.applyScopedProxyMode(scopeMetadata, definitionHolder, this.registry);
    /* 将bean的定义信息放到上下文关联的bean工厂的缓存中，DefaultListableBeanFactory#beanDefinitionMap 
    *  后面加载类的时候会根据这里面的元数据信息生成bean对象
    */
		BeanDefinitionReaderUtils.registerBeanDefinition(definitionHolder, this.registry);
	}
```

初始化bean的堆栈，核心函数为preInstantiateSingletons，BeanFactory会根据扫描到注解的beanmap遍历生成相应的对象，生成好的对象会放到singletonObjects中

```java

instantiateBean:1312, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
createBeanInstance:1213, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
doCreateBean:556, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
createBean:516, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
lambda$doGetBean$0:324, AbstractBeanFactory (org.springframework.beans.factory.support)
getObject:-1, 1852661033 (org.springframework.beans.factory.support.AbstractBeanFactory$$Lambda$175)
getSingleton:226, DefaultSingletonBeanRegistry (org.springframework.beans.factory.support)
doGetBean:322, AbstractBeanFactory (org.springframework.beans.factory.support)
getBean:202, AbstractBeanFactory (org.springframework.beans.factory.support)
preInstantiateSingletons:897, DefaultListableBeanFactory (org.springframework.beans.factory.support)
finishBeanFactoryInitialization:879, AbstractApplicationContext (org.springframework.context.support)
refresh:551, AbstractApplicationContext (org.springframework.context.support)
refresh:143, ServletWebServerApplicationContext (org.springframework.boot.web.servlet.context)
refresh:758, SpringApplication (org.springframework.boot)
refresh:750, SpringApplication (org.springframework.boot)
refreshContext:397, SpringApplication (org.springframework.boot)
run:315, SpringApplication (org.springframework.boot)
run:1237, SpringApplication (org.springframework.boot)
run:1226, SpringApplication (org.springframework.boot)
main:11, SaleMain (a.b.c)

# singletonObjects
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

```

**spring.factories**

```
# PropertySource Loaders
org.springframework.boot.env.PropertySourceLoader=\
org.springframework.boot.env.PropertiesPropertySourceLoader,\
org.springframework.boot.env.YamlPropertySourceLoader

# Run Listeners
org.springframework.boot.SpringApplicationRunListener=\
org.springframework.boot.context.event.EventPublishingRunListener

# Application Context Initializers
org.springframework.context.ApplicationContextInitializer=\
org.springframework.boot.context.ConfigurationWarningsApplicationContextInitializer,\
org.springframework.boot.context.ContextIdApplicationContextInitializer,\
org.springframework.boot.context.config.DelegatingApplicationContextInitializer,\
org.springframework.boot.context.embedded.ServerPortInfoApplicationContextInitializer

# Application Listeners
org.springframework.context.ApplicationListener=\
org.springframework.boot.ClearCachesApplicationListener,\
org.springframework.boot.builder.ParentContextCloserApplicationListener,\
org.springframework.boot.context.FileEncodingApplicationListener,\
org.springframework.boot.context.config.AnsiOutputApplicationListener,\
org.springframework.boot.context.config.ConfigFileApplicationListener,\
org.springframework.boot.context.config.DelegatingApplicationListener,\
org.springframework.boot.liquibase.LiquibaseServiceLocatorApplicationListener,\
org.springframework.boot.logging.ClasspathLoggingApplicationListener,\
org.springframework.boot.logging.LoggingApplicationListener

# Environment Post Processors
org.springframework.boot.env.EnvironmentPostProcessor=\
org.springframework.boot.cloud.CloudFoundryVcapEnvironmentPostProcessor,\
org.springframework.boot.env.SpringApplicationJsonEnvironmentPostProcessor

# Failure Analyzers
org.springframework.boot.diagnostics.FailureAnalyzer=\
org.springframework.boot.diagnostics.analyzer.BeanCurrentlyInCreationFailureAnalyzer,\
org.springframework.boot.diagnostics.analyzer.BeanNotOfRequiredTypeFailureAnalyzer,\
org.springframework.boot.diagnostics.analyzer.BindFailureAnalyzer,\
org.springframework.boot.diagnostics.analyzer.ConnectorStartFailureAnalyzer,\
org.springframework.boot.diagnostics.analyzer.NoUniqueBeanDefinitionFailureAnalyzer,\
org.springframework.boot.diagnostics.analyzer.PortInUseFailureAnalyzer,\
org.springframework.boot.diagnostics.analyzer.ValidationExceptionFailureAnalyzer

# FailureAnalysisReporters
org.springframework.boot.diagnostics.FailureAnalysisReporter=\
org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter

```

### Bean扫描器研读

通过此文的描述要达到的目的在于，我们可以借助于spring提供的jar，实现一个自定义的加载器：通过集成ClassPathScanningCandidateComponentProvider，传入相应的classloader和filter，便可以获取类的自动加载。



Spring一个比较重要的流程就是类的加载，类的加载离不开扫描器，类ClassPathBeanDefinitionScanner则是其中重要的实现。

```java
org.springframework.context.annotation.ClassPathBeanDefinitionScanner
```

核心代码解读



```java
	protected Set<BeanDefinitionHolder> doScan(String... basePackages) {
		Assert.notEmpty(basePackages, "At least one base package must be specified");
		Set<BeanDefinitionHolder> beanDefinitions = new LinkedHashSet<BeanDefinitionHolder>();
		for (String basePackage : basePackages) {
      /* 查找所有的候选Bean实现类，BeanDefinition包含了类的元数据信息，包括注解会分析该流程 */
			Set<BeanDefinition> candidates = findCandidateComponents(basePackage);
			for (BeanDefinition candidate : candidates) {
        /* 获取类的scope，比如是单例还是模型等 */
				ScopeMetadata scopeMetadata = this.scopeMetadataResolver.resolveScopeMetadata(candidate);
				candidate.setScope(scopeMetadata.getScopeName());
        /* 生成实际的beanname，因为有可能会通过注解自定义beanname */
				String beanName = this.beanNameGenerator.generateBeanName(candidate, this.registry);
				if (candidate instanceof AbstractBeanDefinition) {
					postProcessBeanDefinition((AbstractBeanDefinition) candidate, beanName);
				}
				if (candidate instanceof AnnotatedBeanDefinition) {
					AnnotationConfigUtils.processCommonDefinitionAnnotations((AnnotatedBeanDefinition) candidate);
				}
				if (checkCandidate(beanName, candidate)) {
					BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(candidate, beanName);
					definitionHolder =
							AnnotationConfigUtils.applyScopedProxyMode(scopeMetadata, definitionHolder, this.registry);
					beanDefinitions.add(definitionHolder);
					registerBeanDefinition(definitionHolder, this.registry);
				}
			}
		}
		return beanDefinitions;
	}
```





```java
	/**
	 * Scan the class path for candidate components.
	 * @param basePackage the package to check for annotated classes
	 * @return a corresponding Set of autodetected bean definitions
	 */
	public Set<BeanDefinition> findCandidateComponents(String basePackage) {
		Set<BeanDefinition> candidates = new LinkedHashSet<BeanDefinition>();
		try {
			String packageSearchPath = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX +
					resolveBasePackage(basePackage) + '/' + this.resourcePattern;
      /* 通过设置的classloader取类加载器中匹配了查询规则的所有class文件，resource实现类未文件系统相关实现 */
			Resource[] resources = this.resourcePatternResolver.getResources(packageSearchPath);
			boolean traceEnabled = logger.isTraceEnabled();
			boolean debugEnabled = logger.isDebugEnabled();
			for (Resource resource : resources) {
				if (traceEnabled) {
					logger.trace("Scanning " + resource);
				}
				if (resource.isReadable()) {
					try {
            /* 元数据读取类，该类底层通过ClassReader解析了class文件的字节码具体参见ClassReader实现类 */
						MetadataReader metadataReader = this.metadataReaderFactory.getMetadataReader(resource);
            /* 此步骤中你可以定义自己的过滤条件过滤候选类 */
						if (isCandidateComponent(metadataReader)) {
							ScannedGenericBeanDefinition sbd = new ScannedGenericBeanDefinition(metadataReader);
							sbd.setResource(resource);
							sbd.setSource(resource);
							if (isCandidateComponent(sbd)) {
								if (debugEnabled) {
									logger.debug("Identified candidate component class: " + resource);
								}
								candidates.add(sbd);
							}
							else {
								if (debugEnabled) {
									logger.debug("Ignored because not a concrete top-level class: " + resource);
								}
							}
						}
						else {
							if (traceEnabled) {
								logger.trace("Ignored because not matching any filter: " + resource);
							}
						}
					}
					catch (Throwable ex) {
						throw new BeanDefinitionStoreException(
								"Failed to read candidate component class: " + resource, ex);
					}
				}
				else {
					if (traceEnabled) {
						logger.trace("Ignored because not readable: " + resource);
					}
				}
			}
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException("I/O failure during classpath scanning", ex);
		}
		return candidates;
	}
```



class字节文件解析：

org.springframework.asm.ClassReader#ClassReader

### 类加载流程

在下面的堆栈中有个类org.springframework.context.annotation.ConfigurationClassPostProcessor

```
invokeBeanFactoryPostProcessors:98, PostProcessorRegistrationDelegate (org.springframework.context.support)
invokeBeanFactoryPostProcessors:681, AbstractApplicationContext (org.springframework.context.support)
refresh:523, AbstractApplicationContext (org.springframework.context.support)
refresh:737, SpringApplication (org.springframework.boot)
refreshContext:370, SpringApplication (org.springframework.boot)
run:314, SpringApplication (org.springframework.boot)
run:134, SpringApplicationBuilder (org.springframework.boot.builder)
main:24, Application (com.dtwave.dipper.resource.server.provider)
```

这个类是加载类定义的实现类，主体方法实现了以下接口用户注册bean的类定义

```java
public interface BeanDefinitionRegistryPostProcessor extends BeanFactoryPostProcessor {

	/**
	 * Modify the application context's internal bean definition registry after its
	 * standard initialization. All regular bean definitions will have been loaded,
	 * but no beans will have been instantiated yet. This allows for adding further
	 * bean definitions before the next post-processing phase kicks in.
	 * @param registry the bean definition registry used by the application context
	 * @throws org.springframework.beans.BeansException in case of errors
	 */
	void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException;

}
```

经过重重调用到org.springframework.context.annotation.ConfigurationClassParser，其关联了org.springframework.context.annotation.ComponentScanAnnotationParser#ComponentScanAnnotationParser解析类，此类主体解析方法如下：

```java
	/**
	 * Perform a scan within the specified base packages,
	 * returning the registered bean definitions.
	 * <p>This method does <i>not</i> register an annotation config processor
	 * but rather leaves this up to the caller.
	 * @param basePackages the packages to check for annotated classes
	 * @return set of beans registered if any for tooling registration purposes (never {@code null})
	 */
	protected Set<BeanDefinitionHolder> doScan(String... basePackages) {
		Assert.notEmpty(basePackages, "At least one base package must be specified");
		Set<BeanDefinitionHolder> beanDefinitions = new LinkedHashSet<BeanDefinitionHolder>();
    // 遍历所有包路径
		for (String basePackage : basePackages) {
      // 这里是一个很重要的方法，取读取class文件将其加载为class
			Set<BeanDefinition> candidates = findCandidateComponents(basePackage);
			for (BeanDefinition candidate : candidates) {
				ScopeMetadata scopeMetadata = this.scopeMetadataResolver.resolveScopeMetadata(candidate);
				candidate.setScope(scopeMetadata.getScopeName());
				String beanName = this.beanNameGenerator.generateBeanName(candidate, this.registry);
				if (candidate instanceof AbstractBeanDefinition) {
					postProcessBeanDefinition((AbstractBeanDefinition) candidate, beanName);
				}
				if (candidate instanceof AnnotatedBeanDefinition) {
					AnnotationConfigUtils.processCommonDefinitionAnnotations((AnnotatedBeanDefinition) candidate);
				}
				if (checkCandidate(beanName, candidate)) {
					BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(candidate, beanName);
					definitionHolder = AnnotationConfigUtils.applyScopedProxyMode(scopeMetadata, definitionHolder, this.registry);
					beanDefinitions.add(definitionHolder);
					registerBeanDefinition(definitionHolder, this.registry);
				}
			}
		}
		return beanDefinitions;
	}
```

findCandidateComponents解读

```java
	public Set<BeanDefinition> findCandidateComponents(String basePackage) {
		Set<BeanDefinition> candidates = new LinkedHashSet<BeanDefinition>();
		try {
			String packageSearchPath = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX +
					resolveBasePackage(basePackage) + "/" + this.resourcePattern;
      // 从上下文获取class资源列表
			Resource[] resources = this.resourcePatternResolver.getResources(packageSearchPath);
			boolean traceEnabled = logger.isTraceEnabled();
			boolean debugEnabled = logger.isDebugEnabled();
			for (Resource resource : resources) {
				if (traceEnabled) {
					logger.trace("Scanning " + resource);
				}
				if (resource.isReadable()) {
					try {
            // 从CachingMetadataReaderFactory的接口中获取类的元数据信息，包括类的元数据和注解信息
						MetadataReader metadataReader = this.metadataReaderFactory.getMetadataReader(resource);
						if (isCandidateComponent(metadataReader)) {
							ScannedGenericBeanDefinition sbd = new ScannedGenericBeanDefinition(metadataReader);
							sbd.setResource(resource);
							sbd.setSource(resource);
							if (isCandidateComponent(sbd)) {
								if (debugEnabled) {
									logger.debug("Identified candidate component class: " + resource);
								}
								candidates.add(sbd);
							}
							else {
								if (debugEnabled) {
									logger.debug("Ignored because not a concrete top-level class: " + resource);
								}
							}
						}
						else {
							if (traceEnabled) {
								logger.trace("Ignored because not matching any filter: " + resource);
							}
						}
					}
					catch (Throwable ex) {
						throw new BeanDefinitionStoreException(
								"Failed to read candidate component class: " + resource, ex);
					}
				}
				else {
					if (traceEnabled) {
						logger.trace("Ignored because not readable: " + resource);
					}
				}
			}
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException("I/O failure during classpath scanning", ex);
		}
		return candidates;
	}
```



org.springframework.core.type.classreading.CachingMetadataReaderFactory#getMetadataReader

```java
public MetadataReader getMetadataReader(Resource resource) throws IOException {
   if (getCacheLimit() <= 0) {
      return super.getMetadataReader(resource);
   }
   synchronized (this.metadataReaderCache) {
      MetadataReader metadataReader = this.metadataReaderCache.get(resource);
      if (metadataReader == null) {
         metadataReader = super.getMetadataReader(resource);
         this.metadataReaderCache.put(resource, metadataReader);
      }
      return metadataReader;
   }
}
```



SimpleMetadataReader

```java
SimpleMetadataReader(Resource resource, ClassLoader classLoader) throws IOException {
   InputStream is = new BufferedInputStream(resource.getInputStream());
   ClassReader classReader;
   try {
      classReader = new ClassReader(is);
   }
   catch (IllegalArgumentException ex) {
      throw new NestedIOException("ASM ClassReader failed to parse class file - " +
            "probably due to a new Java class file version that isn't supported yet: " + resource, ex);
   }
   finally {
      is.close();
   }

   // 访问者模式，从字节流中读取注解等元数据信息
   AnnotationMetadataReadingVisitor visitor = new AnnotationMetadataReadingVisitor(classLoader);
   classReader.accept(visitor, ClassReader.SKIP_DEBUG);

   this.annotationMetadata = visitor;
   // (since AnnotationMetadataReadingVisitor extends ClassMetadataReadingVisitor)
   this.classMetadata = visitor;
   this.resource = resource;
}
```



### Environment

主要用于解决配置模板、系统变量和系统配置等信息的修改和访问操作。比如我们可以通过实现EnvironmentAware接口来访问Environment来获取application.properties里的配置信息。

```java
public class StandardEnvironment extends AbstractEnvironment {

   /** System environment property source name: {@value} */
   public static final String SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME = "systemEnvironment";

   /** JVM system properties property source name: {@value} */
   public static final String SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME = "systemProperties";


   /**
    * Customize the set of property sources with those appropriate for any standard
    * Java environment:
    * <ul>
    * <li>{@value #SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME}
    * <li>{@value #SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME}
    * </ul>
    * <p>Properties present in {@value #SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME} will
    * take precedence over those in {@value #SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME}.
    * @see AbstractEnvironment#customizePropertySources(MutablePropertySources)
    * @see #getSystemProperties()
    * @see #getSystemEnvironment()
    */
   @Override
   protected void customizePropertySources(MutablePropertySources propertySources) {
      propertySources.addLast(new MapPropertySource(SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, getSystemProperties()));
      propertySources.addLast(new SystemEnvironmentPropertySource(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, getSystemEnvironment()));
   }

}
```

### Spring AOP研读

Spring Aop是Spring非常核心的一项技术，比如spring的事务，及其他一些业务特性或者公共特性的集成都会用到。



查找spring aop的代理流程如下所示，下面会对核心流程进行展开分析：

```java
findEligibleAdvisors:94, AbstractAdvisorAutoProxyCreator (org.springframework.aop.framework.autoproxy)
getAdvicesAndAdvisorsForBean:70, AbstractAdvisorAutoProxyCreator (org.springframework.aop.framework.autoproxy)
wrapIfNecessary:346, AbstractAutoProxyCreator (org.springframework.aop.framework.autoproxy)
getEarlyBeanReference:237, AbstractAutoProxyCreator (org.springframework.aop.framework.autoproxy)
getEarlyBeanReference:882, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
getObject:545, AbstractAutowireCapableBeanFactory$2 (org.springframework.beans.factory.support)
getSingleton:192, DefaultSingletonBeanRegistry (org.springframework.beans.factory.support)
getSingleton:173, DefaultSingletonBeanRegistry (org.springframework.beans.factory.support)
doGetBean:243, AbstractBeanFactory (org.springframework.beans.factory.support)
getBean:202, AbstractBeanFactory (org.springframework.beans.factory.support)
resolveCandidate:208, DependencyDescriptor (org.springframework.beans.factory.config)
doResolveDependency:1138, DefaultListableBeanFactory (org.springframework.beans.factory.support)
resolveDependency:1066, DefaultListableBeanFactory (org.springframework.beans.factory.support)
inject:585, AutowiredAnnotationBeanPostProcessor$AutowiredFieldElement (org.springframework.beans.factory.annotation)
inject:88, InjectionMetadata (org.springframework.beans.factory.annotation)
postProcessPropertyValues:366, AutowiredAnnotationBeanPostProcessor (org.springframework.beans.factory.annotation)
populateBean:1264, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
doCreateBean:553, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
createBean:483, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
getObject:306, AbstractBeanFactory$1 (org.springframework.beans.factory.support)
getSingleton:230, DefaultSingletonBeanRegistry (org.springframework.beans.factory.support)
doGetBean:302, AbstractBeanFactory (org.springframework.beans.factory.support)
getBean:202, AbstractBeanFactory (org.springframework.beans.factory.support)
resolveCandidate:208, DependencyDescriptor (org.springframework.beans.factory.config)
doResolveDependency:1138, DefaultListableBeanFactory (org.springframework.beans.factory.support)
resolveDependency:1066, DefaultListableBeanFactory (org.springframework.beans.factory.support)
inject:585, AutowiredAnnotationBeanPostProcessor$AutowiredFieldElement (org.springframework.beans.factory.annotation)
inject:88, InjectionMetadata (org.springframework.beans.factory.annotation)
postProcessPropertyValues:366, AutowiredAnnotationBeanPostProcessor (org.springframework.beans.factory.annotation)
populateBean:1264, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
doCreateBean:553, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
createBean:483, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
getObject:306, AbstractBeanFactory$1 (org.springframework.beans.factory.support)
getSingleton:230, DefaultSingletonBeanRegistry (org.springframework.beans.factory.support)
doGetBean:302, AbstractBeanFactory (org.springframework.beans.factory.support)
getBean:202, AbstractBeanFactory (org.springframework.beans.factory.support)
resolveCandidate:208, DependencyDescriptor (org.springframework.beans.factory.config)
doResolveDependency:1138, DefaultListableBeanFactory (org.springframework.beans.factory.support)
resolveDependency:1066, DefaultListableBeanFactory (org.springframework.beans.factory.support)
inject:585, AutowiredAnnotationBeanPostProcessor$AutowiredFieldElement (org.springframework.beans.factory.annotation)
inject:88, InjectionMetadata (org.springframework.beans.factory.annotation)
postProcessPropertyValues:366, AutowiredAnnotationBeanPostProcessor (org.springframework.beans.factory.annotation)
populateBean:1264, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
doCreateBean:553, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
createBean:483, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
getObject:306, AbstractBeanFactory$1 (org.springframework.beans.factory.support)
getSingleton:230, DefaultSingletonBeanRegistry (org.springframework.beans.factory.support)
doGetBean:302, AbstractBeanFactory (org.springframework.beans.factory.support)
getBean:202, AbstractBeanFactory (org.springframework.beans.factory.support)
resolveCandidate:208, DependencyDescriptor (org.springframework.beans.factory.config)
doResolveDependency:1138, DefaultListableBeanFactory (org.springframework.beans.factory.support)
resolveDependency:1066, DefaultListableBeanFactory (org.springframework.beans.factory.support)
inject:585, AutowiredAnnotationBeanPostProcessor$AutowiredFieldElement (org.springframework.beans.factory.annotation)
inject:88, InjectionMetadata (org.springframework.beans.factory.annotation)
postProcessPropertyValues:366, AutowiredAnnotationBeanPostProcessor (org.springframework.beans.factory.annotation)
populateBean:1264, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
doCreateBean:553, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
createBean:483, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
getObject:306, AbstractBeanFactory$1 (org.springframework.beans.factory.support)
getSingleton:230, DefaultSingletonBeanRegistry (org.springframework.beans.factory.support)
doGetBean:302, AbstractBeanFactory (org.springframework.beans.factory.support)
getBean:202, AbstractBeanFactory (org.springframework.beans.factory.support)
resolveCandidate:208, DependencyDescriptor (org.springframework.beans.factory.config)
doResolveDependency:1138, DefaultListableBeanFactory (org.springframework.beans.factory.support)
resolveDependency:1066, DefaultListableBeanFactory (org.springframework.beans.factory.support)
inject:585, AutowiredAnnotationBeanPostProcessor$AutowiredFieldElement (org.springframework.beans.factory.annotation)
inject:88, InjectionMetadata (org.springframework.beans.factory.annotation)
postProcessPropertyValues:366, AutowiredAnnotationBeanPostProcessor (org.springframework.beans.factory.annotation)
populateBean:1264, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
doCreateBean:553, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
createBean:483, AbstractAutowireCapableBeanFactory (org.springframework.beans.factory.support)
getObject:306, AbstractBeanFactory$1 (org.springframework.beans.factory.support)
getSingleton:230, DefaultSingletonBeanRegistry (org.springframework.beans.factory.support)
doGetBean:302, AbstractBeanFactory (org.springframework.beans.factory.support)
getBean:197, AbstractBeanFactory (org.springframework.beans.factory.support)
preInstantiateSingletons:761, DefaultListableBeanFactory (org.springframework.beans.factory.support)
finishBeanFactoryInitialization:861, AbstractApplicationContext (org.springframework.context.support)
refresh:541, AbstractApplicationContext (org.springframework.context.support)
refresh:737, SpringApplication (org.springframework.boot)
refreshContext:370, SpringApplication (org.springframework.boot)
run:314, SpringApplication (org.springframework.boot)
loadContext:120, SpringBootContextLoader (org.springframework.boot.test.context)
loadContextInternal:98, DefaultCacheAwareContextLoaderDelegate (org.springframework.test.context.cache)
loadContext:116, DefaultCacheAwareContextLoaderDelegate (org.springframework.test.context.cache)
getApplicationContext:83, DefaultTestContext (org.springframework.test.context.support)
setUpRequestContextIfNecessary:189, ServletTestExecutionListener (org.springframework.test.context.web)
prepareTestInstance:131, ServletTestExecutionListener (org.springframework.test.context.web)
prepareTestInstance:230, TestContextManager (org.springframework.test.context)
createTest:228, SpringJUnit4ClassRunner (org.springframework.test.context.junit4)
runReflectiveCall:287, SpringJUnit4ClassRunner$1 (org.springframework.test.context.junit4)
run:12, ReflectiveCallable (org.junit.internal.runners.model)
methodBlock:289, SpringJUnit4ClassRunner (org.springframework.test.context.junit4)
runChild:247, SpringJUnit4ClassRunner (org.springframework.test.context.junit4)
runChild:94, SpringJUnit4ClassRunner (org.springframework.test.context.junit4)
run:290, ParentRunner$3 (org.junit.runners)
schedule:71, ParentRunner$1 (org.junit.runners)
runChildren:288, ParentRunner (org.junit.runners)
access$000:58, ParentRunner (org.junit.runners)
evaluate:268, ParentRunner$2 (org.junit.runners)
evaluate:61, RunBeforeTestClassCallbacks (org.springframework.test.context.junit4.statements)
evaluate:70, RunAfterTestClassCallbacks (org.springframework.test.context.junit4.statements)
run:363, ParentRunner (org.junit.runners)
run:191, SpringJUnit4ClassRunner (org.springframework.test.context.junit4)
run:137, JUnitCore (org.junit.runner)
startRunnerWithArgs:68, JUnit4IdeaTestRunner (com.intellij.junit4)
startRunnerWithArgs:47, IdeaTestRunner$Repeater (com.intellij.rt.execution.junit)
prepareStreamsAndStart:242, JUnitStarter (com.intellij.rt.execution.junit)
main:70, JUnitStarter (com.intellij.rt.execution.junit)
```



org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator#findEligibleAdvisors

```java
protected List<Advisor> findEligibleAdvisors(Class<?> beanClass, String beanName) {
  /* org.springframework.aop.Advisor的实现类 */
   List<Advisor> candidateAdvisors = findCandidateAdvisors();
  /* 找到当前bean实例可以使用的代理，比如trasaction的代理 */
   List<Advisor> eligibleAdvisors = findAdvisorsThatCanApply(candidateAdvisors, beanClass, beanName);
   extendAdvisors(eligibleAdvisors);
   if (!eligibleAdvisors.isEmpty()) {
      eligibleAdvisors = sortAdvisors(eligibleAdvisors);
   }
   return eligibleAdvisors;
}
```

事务的代理：

org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor





```java
protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
   if (beanName != null && this.targetSourcedBeans.contains(beanName)) {
      return bean;
   }
   if (Boolean.FALSE.equals(this.advisedBeans.get(cacheKey))) {
      return bean;
   }
   if (isInfrastructureClass(bean.getClass()) || shouldSkip(bean.getClass(), beanName)) {
      this.advisedBeans.put(cacheKey, Boolean.FALSE);
      return bean;
   }

   // Create proxy if we have advice.
   Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName, null);
   if (specificInterceptors != DO_NOT_PROXY) {
      this.advisedBeans.put(cacheKey, Boolean.TRUE);
     /* 实例创建代理的过程 */
      Object proxy = createProxy(
            bean.getClass(), beanName, specificInterceptors, new SingletonTargetSource(bean));
      this.proxyTypes.put(cacheKey, proxy.getClass());
      return proxy;
   }

   this.advisedBeans.put(cacheKey, Boolean.FALSE);
   return bean;
}
```

createProxy

```java
/**
* specificInterceptors:拦截器
*/
protected Object createProxy(
      Class<?> beanClass, String beanName, Object[] specificInterceptors, TargetSource targetSource) {

   if (this.beanFactory instanceof ConfigurableListableBeanFactory) {
      AutoProxyUtils.exposeTargetClass((ConfigurableListableBeanFactory) this.beanFactory, beanName, beanClass);
   }

   ProxyFactory proxyFactory = new ProxyFactory();
   proxyFactory.copyFrom(this);

   if (!proxyFactory.isProxyTargetClass()) {
      if (shouldProxyTargetClass(beanClass, beanName)) {
         proxyFactory.setProxyTargetClass(true);
      }
      else {
         evaluateProxyInterfaces(beanClass, proxyFactory);
      }
   }

   Advisor[] advisors = buildAdvisors(beanName, specificInterceptors);
   for (Advisor advisor : advisors) {
      proxyFactory.addAdvisor(advisor);
   }

   proxyFactory.setTargetSource(targetSource);
   customizeProxyFactory(proxyFactory);

   proxyFactory.setFrozen(this.freezeProxy);
   if (advisorsPreFiltered()) {
      proxyFactory.setPreFiltered(true);
   }

   return proxyFactory.getProxy(getProxyClassLoader());
}

	/* 底层返回的代理实例：ObjenesisCglibAopProxy或者JdkDynamicAopProxy */
	public AopProxy createAopProxy(AdvisedSupport config) throws AopConfigException {
		if (config.isOptimize() || config.isProxyTargetClass() || hasNoUserSuppliedProxyInterfaces(config)) {
			Class<?> targetClass = config.getTargetClass();
			if (targetClass == null) {
				throw new AopConfigException("TargetSource cannot determine target class: " +
						"Either an interface or a target is required for proxy creation.");
			}
			if (targetClass.isInterface() || Proxy.isProxyClass(targetClass)) {
				return new JdkDynamicAopProxy(config);
			}
			return new ObjenesisCglibAopProxy(config);
		}
		else {
			return new JdkDynamicAopProxy(config);
		}
	}
```



### Transactional研读

#### 相关原理



| 异常状态                    | PROPAGATION_REQUIRES_NEW <br/>（两个独立事务）               | PROPAGATION_NESTED (B的事务嵌套在A的事务中) | PROPAGATION_REQUIRED (同一个事务) |
| --------------------------- | ------------------------------------------------------------ | ------------------------------------------- | --------------------------------- |
| methodA抛异常 methodB正常   | A回滚，B正常提交                                             | A与B一起回滚                                | A与B一起回滚                      |
| methodA正常 methodB抛异常\  | 1.如果A中捕获B的异常，并没有继续向<br/>上抛异常，则B先回滚，A再正常提交； <br/>2.如果A未捕获B的异常，默认则会<br/>将B的异常向上抛，则B先回滚，A再回滚 | B先回滚，A再正常提交                        | A与B一起回滚                      |
| methodA抛异常 methodB抛异常 | B先回滚，A再回滚                                             | A与B一起回滚                                | A与B一起回滚                      |
| methodA正常 methodB正常     | B先提交，A再提交                                             | A与B一起提交                                | A与B一起提交                      |

#### 代码研读

org.springframework.transaction.interceptor.TransactionInterceptor是Spring的事务处理流程，它是在JdkDynamicAopProxy中作为chain实现的。

JdkDynamicAopProxy核心实现：

```java
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		MethodInvocation invocation;
		Object oldProxy = null;
		boolean setProxyContext = false;

		TargetSource targetSource = this.advised.targetSource;
		Class<?> targetClass = null;
		Object target = null;

		try {

			......

			// Get the interception chain for this method.
			List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);

			// Check whether we have any advice. If we don't, we can fallback on direct
			// reflective invocation of the target, and avoid creating a MethodInvocation.
			if (chain.isEmpty()) {
				// We can skip creating a MethodInvocation: just invoke the target directly
				// Note that the final invoker must be an InvokerInterceptor so we know it does
				// nothing but a reflective operation on the target, and no hot swapping or fancy proxying.
				Object[] argsToUse = AopProxyUtils.adaptArgumentsIfNecessary(method, args);
				retVal = AopUtils.invokeJoinpointUsingReflection(target, method, argsToUse);
			}
			else {
				// 这里的chain为调用链，其中org.springframework.transaction.interceptor.TransactionInterceptor就处于其中
				invocation = new ReflectiveMethodInvocation(proxy, target, method, args, targetClass, chain);
				// Proceed to the joinpoint through the interceptor chain.
				retVal = invocation.proceed();
			}

			......
			return retVal;
		}
		finally {
      ......
		}
	}
```



TransactionInterceptor核心实现

```java
public Object invoke(final MethodInvocation invocation) throws Throwable {
   // Work out the target class: may be {@code null}.
   // The TransactionAttributeSource should be passed the target class
   // as well as the method, which may be from an interface.
   Class<?> targetClass = (invocation.getThis() != null ? AopUtils.getTargetClass(invocation.getThis()) : null);

   // Adapt to TransactionAspectSupport's invokeWithinTransaction...
   return invokeWithinTransaction(invocation.getMethod(), targetClass, new InvocationCallback() {
      @Override
      public Object proceedWithInvocation() throws Throwable {
         return invocation.proceed();
      }
   });
}

	protected Object invokeWithinTransaction(Method method, Class<?> targetClass, final InvocationCallback invocation)
			throws Throwable {

		// If the transaction attribute is null, the method is non-transactional.
    // 事务的隔离级别、传播属性等等
		final TransactionAttribute txAttr = getTransactionAttributeSource().getTransactionAttribute(method, targetClass);
    // 获取事务管理器，比如我们经常使用的Jdbc的事务管理器：DataSourceTransactionManager
		final PlatformTransactionManager tm = determineTransactionManager(txAttr);
		final String joinpointIdentification = methodIdentification(method, targetClass, txAttr);

		if (txAttr == null || !(tm instanceof CallbackPreferringPlatformTransactionManager)) {
			// Standard transaction demarcation with getTransaction and commit/rollback calls.
			TransactionInfo txInfo = createTransactionIfNecessary(tm, txAttr, joinpointIdentification);
			Object retVal = null;
			try {
				// This is an around advice: Invoke the next interceptor in the chain.
				// This will normally result in a target object being invoked.
        // 实际的对象方法调用
				retVal = invocation.proceedWithInvocation();
			}
			catch (Throwable ex) {
				// target invocation exception
				completeTransactionAfterThrowing(txInfo, ex);
				throw ex;
			}
			finally {
				cleanupTransactionInfo(txInfo);
			}
			commitTransactionAfterReturning(txInfo);
			return retVal;
		}

		else {
			// It's a CallbackPreferringPlatformTransactionManager: pass a TransactionCallback in.
			try {
				Object result = ((CallbackPreferringPlatformTransactionManager) tm).execute(txAttr,
						new TransactionCallback<Object>() {
							@Override
							public Object doInTransaction(TransactionStatus status) {
								TransactionInfo txInfo = prepareTransactionInfo(tm, txAttr, joinpointIdentification, status);
								try {
									return invocation.proceedWithInvocation();
								}
								catch (Throwable ex) {
									if (txAttr.rollbackOn(ex)) {
										// A RuntimeException: will lead to a rollback.
										if (ex instanceof RuntimeException) {
											throw (RuntimeException) ex;
										}
										else {
											throw new ThrowableHolderException(ex);
										}
									}
									else {
										// A normal return value: will lead to a commit.
										return new ThrowableHolder(ex);
									}
								}
								finally {
									cleanupTransactionInfo(txInfo);
								}
							}
						});

				// Check result: It might indicate a Throwable to rethrow.
				if (result instanceof ThrowableHolder) {
					throw ((ThrowableHolder) result).getThrowable();
				}
				else {
					return result;
				}
			}
			catch (ThrowableHolderException ex) {
				throw ex.getCause();
			}
		}
	}
```



com.mysql.jdbc.ConnectionImpl#setTransactionIsolation

从以下代码可以看出spring的事务其实是基于jdbc的底层sql实现的。

```java
/**
 * @param level
 * @throws SQLException
 */
public void setTransactionIsolation(int level) throws SQLException {
    synchronized (getConnectionMutex()) {
        checkClosed();

        if (this.hasIsolationLevels) {
            String sql = null;

            boolean shouldSendSet = false;

            if (getAlwaysSendSetIsolation()) {
                shouldSendSet = true;
            } else {
                if (level != this.isolationLevel) {
                    shouldSendSet = true;
                }
            }

            if (getUseLocalSessionState()) {
                shouldSendSet = this.isolationLevel != level;
            }

            if (shouldSendSet) {
                switch (level) {
                    case java.sql.Connection.TRANSACTION_NONE:
                        throw SQLError.createSQLException("Transaction isolation level NONE not supported by MySQL", getExceptionInterceptor());

                    case java.sql.Connection.TRANSACTION_READ_COMMITTED:
                        sql = "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED";

                        break;

                    case java.sql.Connection.TRANSACTION_READ_UNCOMMITTED:
                        sql = "SET SESSION TRANSACTION ISOLATION LEVEL READ UNCOMMITTED";

                        break;

                    case java.sql.Connection.TRANSACTION_REPEATABLE_READ:
                        sql = "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ";

                        break;

                    case java.sql.Connection.TRANSACTION_SERIALIZABLE:
                        sql = "SET SESSION TRANSACTION ISOLATION LEVEL SERIALIZABLE";

                        break;

                    default:
                        throw SQLError.createSQLException("Unsupported transaction isolation level '" + level + "'", SQLError.SQL_STATE_DRIVER_NOT_CAPABLE,
                                getExceptionInterceptor());
                }

                execSQL(null, sql, -1, null, DEFAULT_RESULT_SET_TYPE, DEFAULT_RESULT_SET_CONCURRENCY, false, this.database, null, false);

                this.isolationLevel = level;
            }
        } else {
            throw SQLError.createSQLException("Transaction Isolation Levels are not supported on MySQL versions older than 3.23.36.",
                    SQLError.SQL_STATE_DRIVER_NOT_CAPABLE, getExceptionInterceptor());
        }
    }
}
```

spring 事务是基于事务管理器AbstractPlatformTransactionManager来做的。事务管理期的实现：

```
JtaTransactionManager
DataSourceTransactionManager
```

### MyBatis启动解读

深入了解mybatis执行语句的流程，对于缓存启用及与周边模块关系的理解和应用



### Logging启动解读



Spring的日志系统在spring application启动时，广播启动事件；由EventPublishingRunListener类进行监听器责任链的调用。日志的初始化是其中一个监听器回调执行：

org.springframework.boot.logging.LoggingApplicationListener回调触发日志系统的初始化。

任何第三方调用，比如Logback/log4j或者自定义的logginsystem必须实现org.springframework.boot.logging.LoggingSystem抽象类，自定义的日志系统可以通过环境变量SYSTEM_PROPERTY进行设置



否则会遍历默认的结合，相关代码见下：

```java
private static final Map<String, String> SYSTEMS;

static {
   Map<String, String> systems = new LinkedHashMap<String, String>();
   systems.put("ch.qos.logback.core.Appender",
         "org.springframework.boot.logging.logback.LogbackLoggingSystem");
   systems.put("org.apache.logging.log4j.core.impl.Log4jContextFactory",
         "org.springframework.boot.logging.log4j2.Log4J2LoggingSystem");
   systems.put("java.util.logging.LogManager",
         "org.springframework.boot.logging.java.JavaLoggingSystem");
   SYSTEMS = Collections.unmodifiableMap(systems);
}
```

LoggingSystem介绍

该类是一个抽象类，可以指定日志配置文件的位置（比如自定义日志文件名）、设置日志的级别等

### 参考



org.springframework.beans.factory.support.DefaultListableBeanFactory

https://blog.csdn.net/Taylar_where/article/details/90547570







## Spring 框架

spring framework包含哪些内容？

```
At the heart are the modules of the core container, including a configuration model and a dependency injection mechanism. Beyond that, the Spring Framework provides foundational support for different application architectures, including messaging, transactional data and persistence, and web. It also includes the Servlet-based Spring MVC web framework and, in parallel, the Spring WebFlux reactive web framework.
```



### IoC容器



IoC也叫做DI。也就是说控制反转和依赖注入可以视为同一个概念。

对象通过多种方式（注解、构造体）定义依赖的对象，Spring在启动过程中根据bean的定义利用java反射等技术将其所依赖的bean注入到容器中；这中实例化过程跟直接通过构造体构造对象是相反的。比如我们需要对象A（对象A需要依赖对象B），我们需要先创建对象B才能够得到对象A的完整初始化；而依赖注入的方式跟此相反，容器会先创建对象A放入到容器中，然后再根据其依赖进行注入。

#### 依赖注入的方式



##### xml形式



##### 基于注解

###### `@Autowired`

###### `@Configuration`和`@Primary`

```java
@Configuration
public class MovieConfiguration {

    @Bean
    @Primary
    public MovieCatalog firstMovieCatalog() { ... }

    @Bean
    public MovieCatalog secondMovieCatalog() { ... }

    // ...
}
```



相当于基于配置文件的如下形式：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns:context="http://www.springframework.org/schema/context"
    xsi:schemaLocation="http://www.springframework.org/schema/beans
        https://www.springframework.org/schema/beans/spring-beans.xsd
        http://www.springframework.org/schema/context
        https://www.springframework.org/schema/context/spring-context.xsd">

    <context:annotation-config/>

    <bean class="example.SimpleMovieCatalog" primary="true">
        <!-- inject any dependencies required by this bean -->
    </bean>

    <bean class="example.SimpleMovieCatalog">
        <!-- inject any dependencies required by this bean -->
    </bean>

    <bean id="movieRecommender" class="example.MovieRecommender"/>

</beans>
```



###### `@PostConstruct`



###### `@PreDestroy`



##### 自动扫描



容器可以扫描相关的包路径需要需要被注入的bean，扫描路径的配置可以使用@MapperScan或者**@ComponentScan**



```
@Configuration
@ComponentScan(basePackages = "org.example")
public class AppConfig  {
    // ...
}
```

当然扫描也可以添加过滤器进行过滤，过滤类型如下：



| Filter Type          | Example Expression           | Description                                                  |
| :------------------- | :--------------------------- | :----------------------------------------------------------- |
| annotation (default) | `org.example.SomeAnnotation` | An annotation to be *present* or *meta-present* at the type level in target components. |
| assignable           | `org.example.SomeClass`      | A class (or interface) that the target components are assignable to (extend or implement). |
| aspectj              | `org.example..*Service+`     | An AspectJ type expression to be matched by the target components. |
| regex                | `org\.example\.Default.*`    | A regex expression to be matched by the target components' class names. |
| custom               | `org.example.MyTypeFilter`   | A custom implementation of the `org.springframework.core.type.TypeFilter` interface. |

note

```shell

如果每找一个bean都需要扫描所有的包及路径将会很耗时间，所以指定包的扫描路径不失为一种好方法。


```



#### 容器



#### Bean



## 常用注解



### @Mapper和@Repository

#### 相同点

两个都是注解在Dao上

#### 不同

@Repository需要在Spring中配置扫描地址，然后生成Dao层的Bean才能被注入到Service层中。

@Mapper不需要配置扫描地址，通过xml里面的namespace里面的接口地址，生成了Bean后注入到Service层中。

##常见问题##

###什么情况下事务失效？###
https://www.cnblogs.com/heqiyoujing/p/11221093.html


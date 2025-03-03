/*
 * Copyright (c) 2010-2019. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.springboot.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.DuplicateCommandHandlerResolver;
import org.axonframework.commandhandling.LoggingDuplicateCommandHandlerResolver;
import org.axonframework.commandhandling.SimpleCommandBus;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.common.AxonThreadFactory;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.config.Configuration;
import org.axonframework.config.EventProcessingConfigurer;
import org.axonframework.config.TagsConfiguration;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.SimpleEventBus;
import org.axonframework.eventhandling.TrackedEventMessage;
import org.axonframework.eventhandling.TrackingEventProcessorConfiguration;
import org.axonframework.eventhandling.async.SequencingPolicy;
import org.axonframework.eventhandling.async.SequentialPerAggregatePolicy;
import org.axonframework.eventhandling.gateway.DefaultEventGateway;
import org.axonframework.eventhandling.gateway.EventGateway;
import org.axonframework.eventsourcing.Snapshotter;
import org.axonframework.eventsourcing.eventstore.EmbeddedEventStore;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.StreamableMessageSource;
import org.axonframework.messaging.SubscribableMessageSource;
import org.axonframework.messaging.annotation.HandlerDefinition;
import org.axonframework.messaging.annotation.ParameterResolverFactory;
import org.axonframework.messaging.correlation.CorrelationDataProvider;
import org.axonframework.messaging.correlation.MessageOriginProvider;
import org.axonframework.messaging.interceptors.CorrelationDataInterceptor;
import org.axonframework.queryhandling.DefaultQueryGateway;
import org.axonframework.queryhandling.LoggingQueryInvocationErrorHandler;
import org.axonframework.queryhandling.QueryBus;
import org.axonframework.queryhandling.QueryGateway;
import org.axonframework.queryhandling.QueryInvocationErrorHandler;
import org.axonframework.queryhandling.QueryUpdateEmitter;
import org.axonframework.queryhandling.SimpleQueryBus;
import org.axonframework.serialization.AnnotationRevisionResolver;
import org.axonframework.serialization.ChainingConverter;
import org.axonframework.serialization.JavaSerializer;
import org.axonframework.serialization.RevisionResolver;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.json.JacksonSerializer;
import org.axonframework.serialization.xml.XStreamSerializer;
import org.axonframework.spring.config.AxonConfiguration;
import org.axonframework.spring.eventsourcing.SpringAggregateSnapshotter;
import org.axonframework.springboot.DistributedCommandBusProperties;
import org.axonframework.springboot.EventProcessorProperties;
import org.axonframework.springboot.SerializerProperties;
import org.axonframework.springboot.TagsConfigurationProperties;
import org.axonframework.springboot.util.ConditionalOnMissingQualifiedBean;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

/**
 * @author Allard Buijze
 * @author Josh Long
 */
@org.springframework.context.annotation.Configuration
@AutoConfigureAfter(EventProcessingAutoConfiguration.class)
@EnableConfigurationProperties(value = {
        EventProcessorProperties.class,
        DistributedCommandBusProperties.class,
        SerializerProperties.class,
        TagsConfigurationProperties.class
})
public class AxonAutoConfiguration implements BeanClassLoaderAware {

    private final EventProcessorProperties eventProcessorProperties;
    private final SerializerProperties serializerProperties;
    private final TagsConfigurationProperties tagsConfigurationProperties;
    private final ApplicationContext applicationContext;

    private ClassLoader beanClassLoader;

    public AxonAutoConfiguration(EventProcessorProperties eventProcessorProperties,
                                 SerializerProperties serializerProperties,
                                 TagsConfigurationProperties tagsConfigurationProperties,
                                 ApplicationContext applicationContext) {
        this.eventProcessorProperties = eventProcessorProperties;
        this.serializerProperties = serializerProperties;
        this.tagsConfigurationProperties = tagsConfigurationProperties;
        this.applicationContext = applicationContext;
    }

    @Bean
    public TagsConfiguration tagsConfiguration() {
        return tagsConfigurationProperties.toTagsConfiguration();
    }

    @Bean
    @ConditionalOnMissingBean
    public RevisionResolver revisionResolver() {
        return new AnnotationRevisionResolver();
    }

    @Bean
    @Primary
    @ConditionalOnMissingQualifiedBean(beanClass = Serializer.class, qualifier = "!eventSerializer,messageSerializer")
    public Serializer serializer(RevisionResolver revisionResolver) {
        return buildSerializer(revisionResolver, serializerProperties.getGeneral());
    }

    @Bean
    @Qualifier("messageSerializer")
    @ConditionalOnMissingQualifiedBean(beanClass = Serializer.class, qualifier = "messageSerializer")
    public Serializer messageSerializer(Serializer genericSerializer, RevisionResolver revisionResolver) {
        if (SerializerProperties.SerializerType.DEFAULT.equals(serializerProperties.getMessages())
                || serializerProperties.getGeneral().equals(serializerProperties.getMessages())) {
            return genericSerializer;
        }
        return buildSerializer(revisionResolver, serializerProperties.getMessages());
    }

    @Bean
    @Qualifier("eventSerializer")
    @ConditionalOnMissingQualifiedBean(beanClass = Serializer.class, qualifier = "eventSerializer")
    public Serializer eventSerializer(@Qualifier("messageSerializer") Serializer messageSerializer,
                                      Serializer generalSerializer,
                                      RevisionResolver revisionResolver) {
        if (SerializerProperties.SerializerType.DEFAULT.equals(serializerProperties.getEvents())
                || serializerProperties.getEvents().equals(serializerProperties.getMessages())) {
            return messageSerializer;
        } else if (serializerProperties.getGeneral().equals(serializerProperties.getEvents())) {
            return generalSerializer;
        }
        return buildSerializer(revisionResolver, serializerProperties.getEvents());
    }

    private Serializer buildSerializer(RevisionResolver revisionResolver,
                                       SerializerProperties.SerializerType serializerType) {
        switch (serializerType) {
            case JACKSON:
                Map<String, ObjectMapper> objectMapperBeans = applicationContext.getBeansOfType(ObjectMapper.class);
                ObjectMapper objectMapper = objectMapperBeans.containsKey("defaultAxonObjectMapper")
                        ? objectMapperBeans.get("defaultAxonObjectMapper")
                        : objectMapperBeans.values().stream().findFirst()
                                           .orElseThrow(() -> new NoClassDefFoundError(
                                                   "com/fasterxml/jackson/databind/ObjectMapper"
                                           ));
                ChainingConverter converter = new ChainingConverter(beanClassLoader);
                return JacksonSerializer.builder()
                                        .revisionResolver(revisionResolver)
                                        .converter(converter)
                                        .objectMapper(objectMapper)
                                        .build();
            case JAVA:
                return JavaSerializer.builder().revisionResolver(revisionResolver).build();
            case XSTREAM:
            case DEFAULT:
            default:
                return XStreamSerializer.builder()
                                        .revisionResolver(revisionResolver)
                                        .classLoader(beanClassLoader)
                                        .build();
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public CorrelationDataProvider messageOriginProvider() {
        return new MessageOriginProvider();
    }

    @Qualifier("eventStore")
    @Bean(name = "eventBus")
    @ConditionalOnMissingBean(EventBus.class)
    @ConditionalOnBean(EventStorageEngine.class)
    public EmbeddedEventStore eventStore(EventStorageEngine storageEngine, AxonConfiguration configuration) {
        return EmbeddedEventStore.builder()
                                 .storageEngine(storageEngine)
                                 .messageMonitor(configuration.messageMonitor(EventStore.class, "eventStore"))
                                 .build();
    }

    @ConditionalOnMissingBean
    @Bean
    public CommandGateway commandGateway(CommandBus commandBus) {
        return DefaultCommandGateway.builder().commandBus(commandBus).build();
    }

    @ConditionalOnMissingBean
    @Bean
    public QueryGateway queryGateway(QueryBus queryBus) {
        return DefaultQueryGateway.builder().queryBus(queryBus).build();
    }

    @Bean
    @ConditionalOnMissingBean({EventStorageEngine.class, EventBus.class})
    public SimpleEventBus eventBus(AxonConfiguration configuration) {
        return SimpleEventBus.builder()
                             .messageMonitor(configuration.messageMonitor(EventStore.class, "eventStore"))
                             .build();
    }

    @ConditionalOnMissingBean
    @Bean
    public EventGateway eventGateway(EventBus eventBus) {
        return DefaultEventGateway.builder().eventBus(eventBus).build();
    }

    @ConditionalOnMissingBean(Snapshotter.class)
    @ConditionalOnBean(EventStore.class)
    @Bean
    public SpringAggregateSnapshotter aggregateSnapshotter(Configuration configuration,
                                                           HandlerDefinition handlerDefinition,
                                                           ParameterResolverFactory parameterResolverFactory,
                                                           EventStore eventStore,
                                                           TransactionManager transactionManager) {
        return SpringAggregateSnapshotter.builder()
                                         .repositoryProvider(configuration::repository)
                                         .transactionManager(transactionManager)
                                         .eventStore(eventStore)
                                         .parameterResolverFactory(parameterResolverFactory)
                                         .handlerDefinition(handlerDefinition)
                                         .build();
    }

    @SuppressWarnings("unchecked")
    @Autowired
    public void configureEventHandling(EventProcessingConfigurer eventProcessingConfigurer,
                                       ApplicationContext applicationContext) {
        eventProcessorProperties.getProcessors().forEach((name, settings) -> {
            Function<Configuration, SequencingPolicy<? super EventMessage<?>>> sequencingPolicy =
                    resolveSequencingPolicy(applicationContext, settings);
            eventProcessingConfigurer.registerSequencingPolicy(name, sequencingPolicy);

            if (settings.getMode() == EventProcessorProperties.Mode.TRACKING) {
                TrackingEventProcessorConfiguration config = TrackingEventProcessorConfiguration
                        .forParallelProcessing(settings.getThreadCount())
                        .andBatchSize(settings.getBatchSize())
                        .andInitialSegmentsCount(initialSegmentCount(settings, 1))
                        .andTokenClaimInterval(settings.getTokenClaimInterval(),
                                               settings.getTokenClaimIntervalTimeUnit());
                Function<Configuration, StreamableMessageSource<TrackedEventMessage<?>>> messageSource =
                        resolveMessageSource(applicationContext, settings);
                eventProcessingConfigurer.registerTrackingEventProcessor(name, messageSource, c -> config);
            } else if (settings.getMode() == EventProcessorProperties.Mode.POOLED) {
                eventProcessingConfigurer.registerPooledStreamingEventProcessor(
                        name,
                        resolveMessageSource(applicationContext, settings),
                        (config, builder) -> {
                            ScheduledExecutorService workerExecutorService = Executors.newScheduledThreadPool(
                                    settings.getThreadCount(), new AxonThreadFactory("WorkPackage[" + name + "]")
                            );
                            return builder.workerExecutorService(workerExecutorService)
                                          .initialSegmentCount(initialSegmentCount(settings, 16))
                                          .tokenClaimInterval(tokenClaimIntervalMillis(settings))
                                          .batchSize(settings.getBatchSize());
                        }
                );
            } else {
                if (settings.getSource() == null) {
                    eventProcessingConfigurer.registerSubscribingEventProcessor(name);
                } else {
                    eventProcessingConfigurer.registerSubscribingEventProcessor(
                            name,
                            c -> applicationContext.getBean(settings.getSource(), SubscribableMessageSource.class)
                    );
                }
            }
        });
    }

    private int initialSegmentCount(EventProcessorProperties.ProcessorSettings settings, int defaultCount) {
        return settings.getInitialSegmentCount() != null ? settings.getInitialSegmentCount() : defaultCount;
    }

    private long tokenClaimIntervalMillis(EventProcessorProperties.ProcessorSettings settings) {
        return settings.getTokenClaimIntervalTimeUnit().toMillis(settings.getTokenClaimInterval());
    }

    @SuppressWarnings("unchecked")
    private Function<Configuration, StreamableMessageSource<TrackedEventMessage<?>>> resolveMessageSource(
            ApplicationContext applicationContext, EventProcessorProperties.ProcessorSettings v) {
        Function<Configuration, StreamableMessageSource<TrackedEventMessage<?>>> messageSource;
        if (v.getSource() == null) {
            messageSource = Configuration::eventStore;
        } else {
            messageSource = c -> applicationContext.getBean(v.getSource(), StreamableMessageSource.class);
        }
        return messageSource;
    }

    @SuppressWarnings("unchecked")
    private Function<Configuration, SequencingPolicy<? super EventMessage<?>>> resolveSequencingPolicy(
            ApplicationContext applicationContext, EventProcessorProperties.ProcessorSettings v) {
        Function<Configuration, SequencingPolicy<? super EventMessage<?>>> sequencingPolicy;
        if (v.getSequencingPolicy() != null) {
            sequencingPolicy = c -> applicationContext.getBean(v.getSequencingPolicy(), SequencingPolicy.class);
        } else {
            sequencingPolicy = c -> SequentialPerAggregatePolicy.instance();
        }
        return sequencingPolicy;
    }

    @Bean
    @ConditionalOnMissingBean
    public DuplicateCommandHandlerResolver duplicateCommandHandlerResolver() {
        return LoggingDuplicateCommandHandlerResolver.instance();
    }

    @ConditionalOnMissingBean(
            ignoredType = {
                    "org.axonframework.commandhandling.distributed.DistributedCommandBus",
                    "org.axonframework.axonserver.connector.command.AxonServerCommandBus"
            },
            value = CommandBus.class
    )
    @Qualifier("localSegment")
    @Bean
    public SimpleCommandBus commandBus(TransactionManager txManager, AxonConfiguration axonConfiguration,
                                       DuplicateCommandHandlerResolver duplicateCommandHandlerResolver) {
        SimpleCommandBus commandBus =
                SimpleCommandBus.builder()
                                .transactionManager(txManager)
                                .duplicateCommandHandlerResolver(duplicateCommandHandlerResolver)
                                .messageMonitor(axonConfiguration.messageMonitor(CommandBus.class, "commandBus"))
                                .build();
        commandBus.registerHandlerInterceptor(
                new CorrelationDataInterceptor<>(axonConfiguration.correlationDataProviders())
        );
        return commandBus;
    }

    @ConditionalOnMissingBean(value = {QueryBus.class, QueryInvocationErrorHandler.class})
    @Qualifier("localSegment")
    @Bean
    public SimpleQueryBus queryBus(AxonConfiguration axonConfiguration, TransactionManager transactionManager) {
        return SimpleQueryBus.builder()
                             .messageMonitor(axonConfiguration.messageMonitor(QueryBus.class, "queryBus"))
                             .transactionManager(transactionManager)
                             .errorHandler(axonConfiguration.getComponent(
                                     QueryInvocationErrorHandler.class,
                                     () -> LoggingQueryInvocationErrorHandler.builder().build()
                             ))
                             .queryUpdateEmitter(axonConfiguration.getComponent(QueryUpdateEmitter.class))
                             .build();
    }

    @ConditionalOnBean(QueryInvocationErrorHandler.class)
    @ConditionalOnMissingBean(QueryBus.class)
    @Qualifier("localSegment")
    @Bean
    public SimpleQueryBus queryBus(AxonConfiguration axonConfiguration,
                                   TransactionManager transactionManager,
                                   QueryInvocationErrorHandler eh) {
        return SimpleQueryBus.builder()
                             .messageMonitor(axonConfiguration.messageMonitor(QueryBus.class, "queryBus"))
                             .transactionManager(transactionManager)
                             .errorHandler(eh)
                             .queryUpdateEmitter(axonConfiguration.getComponent(QueryUpdateEmitter.class))
                             .build();
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.beanClassLoader = classLoader;
    }
}

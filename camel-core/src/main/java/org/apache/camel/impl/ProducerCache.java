/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.impl;

import java.util.Map;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.FailedToCreateProducerException;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.ProducerCallback;
import org.apache.camel.ServicePoolAware;
import org.apache.camel.spi.ServicePool;
import org.apache.camel.util.EventHelper;
import org.apache.camel.util.LRUCache;
import org.apache.camel.util.ServiceHelper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import static org.apache.camel.util.ObjectHelper.wrapRuntimeCamelException;

/**
 * Cache containing created {@link Producer}.
 *
 * @version $Revision$
 */
public class ProducerCache extends ServiceSupport {
    private static final transient Log LOG = LogFactory.getLog(ProducerCache.class);

    private final Map<String, Producer> producers;
    private final ServicePool<Endpoint, Producer> pool;
    private final CamelContext context;

    // TODO: Have easy configuration of pooling in Camel

    public ProducerCache(CamelContext context) {
        this(context, context.getProducerServicePool());
    }

    public ProducerCache(CamelContext context, ServicePool<Endpoint, Producer> producerServicePool) {
        this(context, producerServicePool, new LRUCache<String, Producer>(1000));
    }

    public ProducerCache(CamelContext context, ServicePool<Endpoint, Producer> producerServicePool, Map<String, Producer> cache) {
        this.context = context;
        this.pool = producerServicePool;
        this.producers = cache;
    }

    /**
     * Acquires a pooled producer which you <b>must</b> release back again after usage using the
     * {@link #releaseProducer(org.apache.camel.Endpoint, org.apache.camel.Producer)} method.
     *
     * @param endpoint the endpoint
     * @return the producer
     */
    public Producer acquireProducer(Endpoint endpoint) {
        return doGetProducer(endpoint, true);
    }

    /**
     * Releases an acquired producer back after usage.
     *
     * @param endpoint the endpoint
     * @param producer the producer to release
     * @throws Exception can be thrown if error stopping producer if that was needed.
     */
    public void releaseProducer(Endpoint endpoint, Producer producer) throws Exception {
        if (producer instanceof ServicePoolAware) {
            // release back to the pool
            pool.release(endpoint, producer);
        } else if (!producer.isSingleton()) {
            // stop non singleton producers as we should not leak resources
            producer.stop();
        }
    }

    /**
     * Sends the exchange to the given endpoint
     *
     * @param endpoint the endpoint to send the exchange to
     * @param exchange the exchange to send
     */
    public void send(Endpoint endpoint, Exchange exchange) {
        try {
            sendExchange(endpoint, null, null, exchange);
        } catch (Exception e) {
            throw wrapRuntimeCamelException(e);
        }
    }

    /**
     * Sends an exchange to an endpoint using a supplied
     * {@link Processor} to populate the exchange
     *
     * @param endpoint the endpoint to send the exchange to
     * @param processor the transformer used to populate the new exchange
     * @return the exchange
     */
    public Exchange send(Endpoint endpoint, Processor processor) {
        try {
            return sendExchange(endpoint, null, processor, null);
        } catch (Exception e) {
            throw wrapRuntimeCamelException(e);
        }
    }

    /**
     * Sends an exchange to an endpoint using a supplied
     * {@link Processor} to populate the exchange
     *
     * @param endpoint the endpoint to send the exchange to
     * @param pattern the message {@link ExchangePattern} such as
     *   {@link ExchangePattern#InOnly} or {@link ExchangePattern#InOut}
     * @param processor the transformer used to populate the new exchange
     * @return the exchange
     */
    public Exchange send(Endpoint endpoint, ExchangePattern pattern, Processor processor) {
        try {
            return sendExchange(endpoint, pattern, processor, null);
        } catch (Exception e) {
            throw wrapRuntimeCamelException(e);
        }
    }

    /**
     * Sends an exchange to an endpoint using a supplied callback
     *
     * @param endpoint  the endpoint to send the exchange to
     * @param exchange  the exchange, can be <tt>null</tt> if so then create a new exchange from the producer
     * @param pattern   the exchange pattern, can be <tt>null</tt>
     * @param callback  the callback
     * @return the response from the callback
     * @throws Exception if an internal processing error has occurred.
     */
    public <T> T doInProducer(Endpoint endpoint, Exchange exchange, ExchangePattern pattern, ProducerCallback<T> callback) throws Exception {
        // get the producer and we do not mind if its pooled as we can handle returning it back to the pool
        Producer producer = doGetProducer(endpoint, true);

        if (producer == null) {
            if (isStopped()) {
                LOG.warn("Ignoring exchange sent after processor is stopped: " + exchange);
                return null;
            } else {
                throw new IllegalStateException("No producer, this processor has not been started: " + this);
            }
        }

        long start = 0;
        if (exchange != null) {
            // record timing for sending the exchange using the producer
            start = System.currentTimeMillis();
        }

        try {
            // invoke the callback
            return callback.doInProducer(producer, exchange, pattern);
        } finally {
            if (exchange != null) {
                long timeTaken = System.currentTimeMillis() - start;
                // emit event that the exchange was sent to the endpoint
                EventHelper.notifyExchangeSent(exchange.getContext(), exchange, endpoint, timeTaken);
            }
            if (producer instanceof ServicePoolAware) {
                // release back to the pool
                pool.release(endpoint, producer);
            } else if (!producer.isSingleton()) {
                // stop non singleton producers as we should not leak resources
                producer.stop();
            }
        }
    }

    protected Exchange sendExchange(final Endpoint endpoint, ExchangePattern pattern,
                                    final Processor processor, Exchange exchange) throws Exception {
        return doInProducer(endpoint, exchange, pattern, new ProducerCallback<Exchange>() {
            public Exchange doInProducer(Producer producer, Exchange exchange, ExchangePattern pattern) throws Exception {
                if (exchange == null) {
                    exchange = pattern != null ? producer.createExchange(pattern) : producer.createExchange();
                }

                if (processor != null) {
                    // lets populate using the processor callback
                    processor.process(exchange);
                }

                // now lets dispatch
                if (LOG.isDebugEnabled()) {
                    LOG.debug(">>>> " + endpoint + " " + exchange);
                }

                // set property which endpoint we send to
                exchange.setProperty(Exchange.TO_ENDPOINT, endpoint.getEndpointUri());

                // send the exchange using the processor
                long start = System.currentTimeMillis();
                try {
                    producer.process(exchange);
                } finally {
                    // emit event that the exchange was sent to the endpoint
                    long timeTaken = System.currentTimeMillis() - start;
                    EventHelper.notifyExchangeSent(exchange.getContext(), exchange, endpoint, timeTaken);
                }
                return exchange;
            }
        });
    }

    protected synchronized Producer doGetProducer(Endpoint endpoint, boolean pooled) {
        String key = endpoint.getEndpointUri();
        Producer answer = producers.get(key);
        if (pooled && answer == null) {
            // try acquire from connection pool
            answer = pool.acquire(endpoint);
        }

        if (answer == null) {
            // create a new producer
            try {
                answer = endpoint.createProducer();
                // must then start service so producer is ready to be used
                ServiceHelper.startService(answer);
            } catch (Exception e) {
                throw new FailedToCreateProducerException(endpoint, e);
            }

            // add producer to cache or pool if applicable
            if (pooled && answer instanceof ServicePoolAware) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Adding to producer service pool with key: " + endpoint + " for producer: " + answer);
                }
                answer = pool.addAndAcquire(endpoint, answer);
            } else if (answer.isSingleton()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Adding to producer cache with key: " + endpoint + " for producer: " + answer);
                }
                producers.put(key, answer);
            }
        }

        return answer;
    }

    protected void doStop() throws Exception {
        ServiceHelper.stopServices(producers);
        producers.clear();

        ServiceHelper.stopServices(pool);
    }

    protected void doStart() throws Exception {
        ServiceHelper.startServices(pool);
    }

    /**
     * Returns the current size of the producer cache
     *
     * @return the current size
     */
    int size() {
        return producers.size();
    }

}

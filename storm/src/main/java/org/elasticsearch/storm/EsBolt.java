/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.storm;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.elasticsearch.hadoop.EsHadoopException;
import org.elasticsearch.hadoop.rest.InitializationUtils;
import org.elasticsearch.hadoop.rest.RestService;
import org.elasticsearch.hadoop.rest.RestService.PartitionWriter;
import org.elasticsearch.hadoop.serialization.JdkBytesConverter;
import org.elasticsearch.hadoop.serialization.MapFieldExtractor;
import org.elasticsearch.storm.cfg.StormSettings;
import org.elasticsearch.storm.serialization.StormValueWriter;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichBolt;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Tuple;
import static org.elasticsearch.hadoop.cfg.ConfigurationOptions.*;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class EsBolt implements IRichBolt {

    private transient static Log log = LogFactory.getLog(EsBolt.class);

    private Map boltConfig = new LinkedHashMap();

    private transient PartitionWriter writer;
    private transient boolean flushOnTickTuple = true;
    private transient boolean ackWrites = false;

    private transient List<Tuple> inflightTuples = null;
    private transient OutputCollector collector;

    public EsBolt(String target) {
        boltConfig.put(ES_RESOURCE_WRITE, target);
    }

    public EsBolt(String target, Map configuration) {
        boltConfig.putAll(configuration);
        boltConfig.put(ES_RESOURCE_WRITE, target);
    }

    @Override
    public void prepare(Map conf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;

        LinkedHashMap copy = new LinkedHashMap(conf);
        copy.putAll(boltConfig);

        StormSettings settings = new StormSettings(copy);
        flushOnTickTuple = settings.getStormTickTupleFlush();
        ackWrites = settings.getStormBoltAck();

        // trigger manual flush
        if (ackWrites) {
            settings.setProperty(ES_BATCH_FLUSH_MANUAL, Boolean.TRUE.toString());

            // align Bolt / es-hadoop batch settings
            int numberOfEntries = settings.getStormBulkSize();
            settings.setProperty(ES_BATCH_SIZE_ENTRIES, String.valueOf(numberOfEntries));

            inflightTuples = new ArrayList<Tuple>(numberOfEntries + 1);
        }

        int totalTasks = context.getComponentTasks(context.getThisComponentId()).size();

        InitializationUtils.setValueWriterIfNotSet(settings, StormValueWriter.class, log);
        InitializationUtils.setBytesConverterIfNeeded(settings, JdkBytesConverter.class, log);
        InitializationUtils.setFieldExtractorIfNotSet(settings, MapFieldExtractor.class, log);

        writer = RestService.createWriter(settings, context.getThisTaskIndex(), totalTasks, log);
    }

    @Override
    public void execute(Tuple input) {
        if (TupleUtils.isTickTuple(input)) {
            flush();
        }
        else {
            if (ackWrites) {
                inflightTuples.add(input);
            }
            try {
                writer.repository.writeToIndex(input);
                if (!ackWrites) {
                    collector.ack(input);
                }
            } catch (RuntimeException ex) {
                if (!ackWrites) {
                    collector.fail(input);
                }
                throw ex;
            }
        }
    }

    private void flush() {
        if (flushOnTickTuple) {
            if (ackWrites) {
                flushWithAck();
            }
            else {
                flushNoAck();
            }
        }
    }

    private void flushWithAck() {
        BitSet flush = null;

        try {
            flush = writer.repository.tryFlush();
            writer.repository.discard();
        } catch (EsHadoopException ex) {
            // fail all recorded tuples
            for (Tuple input : inflightTuples) {
                collector.fail(input);
            }
            inflightTuples.clear();
            throw ex;
        }

        for (int index = 0; index < inflightTuples.size(); index++) {
            Tuple tuple = inflightTuples.get(index);
            // bit set means the entry hasn't been removed and thus wasn't written to ES
            if (flush.get(index)) {
                collector.fail(tuple);
            }
            else {
                collector.ack(tuple);
            }
        }

        // clear everything in bulk to prevent 'noisy' remove()
        inflightTuples.clear();
    }

    private void flushNoAck() {
        writer.repository.flush();
    }

    @Override
    public void cleanup() {
        if (writer != null) {
            try {
                flush();
            } finally {
                writer.close();
                writer = null;
            }
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {}

    @Override
    public Map<String, Object> getComponentConfiguration() {
        return null;
    }
}
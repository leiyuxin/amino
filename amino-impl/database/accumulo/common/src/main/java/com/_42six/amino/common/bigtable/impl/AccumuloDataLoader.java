package com._42six.amino.common.bigtable.impl;

import com._42six.amino.common.AminoConfiguration;
import com._42six.amino.data.DataLoader;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.mapreduce.AccumuloInputFormat;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.user.WholeRowIterator;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.util.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;

import java.io.IOException;
import java.util.*;

public abstract class AccumuloDataLoader implements DataLoader {
    private static final String INSTANCE = "bigtable.instance";
    private static final String ZOOKEEPERS = "zookeepers";
    private static final String TABLE = "bigtable.table";
    private static final String USERNAME = "bigtable.username";
    private static final String PASSWORD = "bigtable.password";
    private static final String AUTHORIZATIONS = "bigtable.authorizations";

    private static final String ROW_IDS = "loader.rowIds";
    private static final String ROW_SEPARATOR = "loader.separator";
    private static final String DATA_TYPE = "loader.dataType";

    private final Hashtable<Text, Text> bucketsAndDisplayNames = new Hashtable<Text, Text>();

    @SuppressWarnings("rawtypes")
    RecordReader recordReader = null;
    private Configuration config;

    @Override
    public InputFormat<Key, Value> getInputFormat() {
        return new AccumuloInputFormat();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void setRecordReader(RecordReader recordReader) throws IOException {
        this.recordReader = recordReader;
    }

    @Override
    public void initializeFormat(Job job) throws IOException {
        final Configuration conf = job.getConfiguration();
        AminoConfiguration.loadDefault(conf, AccumuloDataLoader.class.getSimpleName(), true);
        String instanceName = conf.get(INSTANCE);
        String zookeeperInfo = conf.get(ZOOKEEPERS);
        String tableName = conf.get(TABLE);
        String userName = conf.get(USERNAME);
        String password = conf.get(PASSWORD);
        String authorizations = conf.get(AUTHORIZATIONS);
        String rowIds = conf.get(ROW_IDS);
        String rowSeparator = conf.get(ROW_SEPARATOR, ",");
        String dataType = conf.get(DATA_TYPE, "PARSED"); // Valid choices are RAW, PARSED, or TDF
        final List<Range> ranges = new ArrayList<Range>();

        for(String row : rowIds.split(rowSeparator)){
            ranges.add(new Range(row));
        }

        // TODO - Add ability to scan on temporal ranges

        final List<Pair<Text, Text>> columns = new ArrayList<Pair<Text, Text>>(1);
        columns.add(new Pair<Text, Text>(new Text("DATA_TYPE"), new Text(dataType)));

        System.out.println("Grabbing data from table '" + tableName + "'");

        AccumuloInputFormat.setZooKeeperInstance(conf, instanceName, zookeeperInfo);
        AccumuloInputFormat.setInputInfo(conf, userName, password.getBytes(), tableName, new Authorizations(authorizations.getBytes()));
        AccumuloInputFormat.addIterator(conf, new IteratorSetting(30, WholeRowIterator.class));
        AccumuloInputFormat.fetchColumns(conf, columns);
        AccumuloInputFormat.setRanges(conf, ranges);
    }

    @Override
    public MapWritable getNext() throws IOException {
        Key key;
        Value value;
        try {
            if(!this.recordReader.nextKeyValue()) {
                return null;
            }

            key = (Key) this.recordReader.getCurrentKey();
            value = (Value) this.recordReader.getCurrentValue();
        } catch (InterruptedException e) {
            return null;
        }

        if (key == null || value == null) {
            return null;
        }

        return processWholeRow(key, value);
    }

    /**
     * Process a whole row from the WholeRowIterator.  This will generally be of the form:
     *
     * for(Map.Entry<Key,Value> entry : WholeRowIterator.decodeRow(key,value).entrySet()){
     *     process values from entry
     *     place results into outputMap
     * }
     *
     * @param key The Key returned from the WholeRowIterator
     * @param value The Value returned from the WholeRowIterator
     *
     * @return MapWritable with all of the bucketed values
     */
    protected abstract MapWritable processWholeRow(Key key, Value value);

    @Override
    public List<Text> getBuckets() {
        return new LinkedList<Text>(bucketsAndDisplayNames.keySet());
    }

    @Override
    public Hashtable<Text,Text> getBucketDisplayNames() {
        return bucketsAndDisplayNames;
    }

    @Override
    public String getDataSourceName() {
        return "Warehaus";
    }

    @Override
    public void setConfig(Configuration config) {
        this.config = config;
    }

    @Override
    public String getVisibility() {
        return "U";
    }

    @Override
    public String getDataSetName(MapWritable mw) {
        return "Warehuas DS";
    }

    @Override
    public String getHumanReadableVisibility() {
        return "UNCLASSIFIED";
    }

    @Override
    public boolean canReadFrom(InputSplit inputSplit)
    {
        return true;
    }

    @Override
    public MapWritable getNextKey(MapWritable currentKey) throws IOException,
            InterruptedException {
        return currentKey;
    }
}

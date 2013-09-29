package com._42six.amino.bitmap;

import com._42six.amino.common.AminoConfiguration;
import com._42six.amino.common.Metadata;
import com._42six.amino.common.accumulo.*;
import com._42six.amino.common.bigtable.TableConstants;
import com.google.gson.Gson;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.client.mapreduce.AccumuloOutputFormat;
import org.apache.accumulo.core.data.Mutation;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import java.io.IOException;

/**
 * Job for importing the metadata information from the framework driver into the Accumulo metadata table. Also creates
 * any tables that might be missing
 */
public class DatabasePrepJob extends Configured implements Tool {

    static Text metaTableText;

    private static boolean createTables(Configuration conf) throws IOException
    {
        // AminoConfiguration.loadDefault(conf, "AminoDefaults", true);
        String instanceName = conf.get("bigtable.instance");
        String zooKeepers = conf.get("bigtable.zookeepers");
        String user = conf.get("bigtable.username");
        String password = conf.get("bigtable.password");
        String metaTable = conf.get("amino.metadataTable");
        String hypoTable = conf.get("amino.hypothesisTable");
        String resultTable = conf.get("amino.queryResultTable");
        String membershipTable = conf.get("amino.groupMembershipTable");
        String groupHypothesisLUTable = conf.get("amino.groupHypothesisLUT");
        String groupMetadataTable = conf.get("amino.groupMetadataTable");
        boolean blastMeta = conf.getBoolean("amino.first.run", false);

        final TableOperations tableOps = IteratorUtils.connect(instanceName, zooKeepers, user, password).tableOperations();

        boolean success = IteratorUtils.createTable(tableOps, metaTable, blastMeta, true);
        if (success) success = IteratorUtils.createTable(tableOps, hypoTable, false, false);
        if (success) success = IteratorUtils.createTable(tableOps, resultTable, false, false);
        if (success) success = IteratorUtils.createTable(tableOps, membershipTable, false, false);
        if (success) success = IteratorUtils.createTable(tableOps, groupHypothesisLUTable, false, false);
        if (success) success = IteratorUtils.createTable(tableOps, groupMetadataTable, false, false);

        return success;
    }

    public static class MetadataConsolidatorReducer
            extends Reducer<Text, Text, Text, Mutation> {

        static final Gson gson = new Gson();


        private <T extends Metadata & BtMetadata> void writeMutations(Class<T> cls, Iterable<Text> jsonValues, Context context)
                throws IOException, InterruptedException {
            T combinedMeta = null;
            for(Text value : jsonValues){
                final T meta = gson.fromJson(value.toString(), cls);
                if(combinedMeta == null) {
                    combinedMeta = meta;
                } else {
                    if(combinedMeta.id.compareTo(meta.id) == 0){
                        combinedMeta.combine(meta);
                    } else {
                        context.write(metaTableText, combinedMeta.createMutation());
                        combinedMeta = meta;
                    }
                }
            }
            final Mutation mutation = combinedMeta.createMutation();
            context.write(metaTableText, mutation);
        }

        /**
         * Takes all of the JSON objects of a particular type, combines them, and creates the Mutation for inserting
         * into the Accumulo table
         * @param metadataType The type of the metadata to combine
         * @param jsonValues The serialized JSON object to combine
         * @param context The MR context to write to Accumulo
         * @throws java.io.IOException
         * @throws InterruptedException
         */
        public void reduce(Text metadataType, Iterable<Text> jsonValues, Context context) throws IOException, InterruptedException
        {
            final String type = metadataType.toString();

            if(type.compareTo(TableConstants.BUCKET_PREFIX) == 0){
                writeMutations(BtBucketMetadata.class, jsonValues, context);
            } else if(type.compareTo(TableConstants.DATASOURCE_PREFIX) == 0){
                writeMutations(BtDatasourceMetadata.class, jsonValues, context);
            } else if (type.compareTo(TableConstants.DOMAIN_PREFIX) == 0){
                writeMutations(BtDomainMetadata.class, jsonValues, context);
            } else if(type.compareTo(TableConstants.FEATURE_PREFIX) == 0){
                writeMutations(BtFeatureMetadata.class, jsonValues, context);
            } else {
                throw new IOException("Unknown metadata type '" + type + ";");
            }
        }
    }

    public int run(String[] args) throws Exception {
        System.out.println("\n=============================== DatabasePrepJob ================================\n");

        final Configuration conf = getConf();
        AminoConfiguration.loadDefault(conf, "AminoDefaults", true);
        final Job job = new Job(conf, "Amino BT meta importer");
        job.setJarByClass(this.getClass());

        // Get config values
        final String instanceName = conf.get("bigtable.instance");
        final String zooKeepers = conf.get("bigtable.zookeepers");
        final String user = conf.get("bigtable.username");
        final byte[] password = conf.get("bigtable.password").getBytes();
        //final String metadataTable = conf.get("amino.metadataTable");
        final String metadataTable = conf.get("amino.metadataTable") + IteratorUtils.TEMP_SUFFIX; //You want to make sure you use the temp here even if blastIndex is false
        metaTableText = new Text(metadataTable);
        final String metadataDir = conf.get("amino.output") + "/cache/metadata";

        // TODO - Verify that all of the params above were not null

        job.setNumReduceTasks(1); // This needs to be 1

        // Mapper - use the IdentityMapper
        job.setMapOutputKeyClass(Text.class);

        // Reducer
        job.setReducerClass(MetadataConsolidatorReducer.class);

        // Inputs
        SequenceFileInputFormat.addInputPath(job, new Path(metadataDir));
        job.setInputFormatClass(SequenceFileInputFormat.class);

        // Outputs
        job.setOutputFormatClass(AccumuloOutputFormat.class);
        AccumuloOutputFormat.setZooKeeperInstance(job, instanceName, zooKeepers);
        AccumuloOutputFormat.setOutputInfo(job, user, password, true, metadataTable);

        // Create the tables if they don't exist
        boolean complete = createTables(conf);
        if(complete){
            complete = job.waitForCompletion(true);
        }

        return complete ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        Configuration conf = new Configuration();
        conf.set(AminoConfiguration.DEFAULT_CONFIGURATION_PATH_KEY, args[0]); // TODO: use flag instead of positional
        AminoConfiguration.loadDefault(conf, "AminoDefaults", true);

        int res = ToolRunner.run(conf, new DatabasePrepJob(), args);
        System.exit(res);
    }
}
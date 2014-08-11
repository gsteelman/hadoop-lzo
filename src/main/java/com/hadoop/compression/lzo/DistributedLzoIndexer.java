package com.hadoop.compression.lzo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.hadoop.compression.lzo.util.CompatibilityUtil;
import com.hadoop.mapreduce.LzoIndexOutputFormat;
import com.hadoop.mapreduce.LzoSplitInputFormat;
import com.hadoop.mapreduce.LzoSplitRecordReader;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

public class DistributedLzoIndexer extends Configured implements Tool {
  private static final Log LOG = LogFactory.getLog(DistributedLzoIndexer.class);

  private final String LZO_EXTENSION = new LzopCodec().getDefaultExtension();
  
  private final String SKIP_INDEXING_SMALL_FILES_KEY = "skip_indexing_small_files";
  private final String RECURSIVE_INDEXING_KEY = "recursive_indexing";
  private final boolean SKIP_INDEXING_SMALL_FILES_DEFAULT = false;
  private final boolean RECURSIVE_INDEXING_DEFAULT = true;
  private boolean skipIndexingSmallFiles = this.SKIP_INDEXING_SMALL_FILES_DEFAULT;
  private boolean recursiveIndexing = this.RECURSIVE_INDEXING_DEFAULT;

  private Configuration conf = getConf();

  /**
   * Accepts paths not ending in /_temporary.
   */
  private final PathFilter nonTemporaryFilter = new PathFilter() {
    @Override
    public boolean accept(Path path) {
      return !path.toString().endsWith("/_temporary");
    }
  };

  /**
   * Accepts paths pointing to files with length greater than a block size.
   */
  private final PathFilter bigFileFilter = new PathFilter() {
    @Override
    public boolean accept(Path path) {
      FileStatus status;
      try {
        FileSystem fs = path.getFileSystem(getConf());
        status = fs.getFileStatus(path);
      } catch (IOException e) {
        LOG.info("Unable to get status of path " + path);
        return false;
      }
      return status.getLen() >= status.getBlockSize() ? true : false;
    }
  };

  private void visitPath(Path path, PathFilter pathFilter, List<Path> accumulator, boolean recursive) {
    try {
      FileSystem fs = path.getFileSystem(this.conf);
      FileStatus fileStatus = fs.getFileStatus(path);

      if (fileStatus.isDirectory()) {
        if (recursive) {
          FileStatus[] children = fs.listStatus(path, pathFilter);
          for (FileStatus childStatus : children) {
            visitPath(childStatus.getPath(), pathFilter, accumulator, recursive);
          }
        } else {
          LOG.info("[SKIP] Path " + path + " is a directory and recursion is not enabled.");
        }
      } else if (shouldIndexPath(path, fs)) {
        accumulator.add(path);
      }
    } catch (IOException ioe) {
      LOG.warn("Error walking path: " + path, ioe);
    }
  }

  private boolean shouldIndexPath(Path path, FileSystem fs) throws IOException {
    if (path.toString().endsWith(LZO_EXTENSION)) {
      if (this.skipIndexingSmallFiles && !this.bigFileFilter.accept(path)) {
        LOG.info("[SKIP] Skip indexing small files enabled and " + path + " is too small");
        return false;
      }

      Path lzoIndexPath = path.suffix(LzoIndex.LZO_INDEX_SUFFIX);
      if (fs.exists(lzoIndexPath)) {
        // If the index exists and is of nonzero size, we're already done.
        // We re-index a file with a zero-length index, because every file has at least one block.
        if (fs.getFileStatus(path).getLen() > 0) {
          LOG.info("[SKIP] LZO index file already exists for " + path);
          return false;
        } else {
          LOG.info("Adding LZO file " + path + " to indexing list (index file exists but is zero length)");
          return true;
        }
      } else {
        // If no index exists, we need to index the file.
        LOG.info("Adding LZO file " + path + " to indexing list (no index currently exists)");
        return true;
      }
    }
    return false;
  }

  public int run(String[] args) throws Exception {
    if (args.length == 0 || (args.length == 1 && "--help".equals(args[0]))) {
      printUsage();
      ToolRunner.printGenericCommandUsage(System.err);
      return -1;
    }

    this.conf = getConf();

    this.skipIndexingSmallFiles =
        this.conf.getBoolean(SKIP_INDEXING_SMALL_FILES_KEY, this.SKIP_INDEXING_SMALL_FILES_DEFAULT);

    // Find paths to index based on recursive/not
    this.recursiveIndexing = this.conf.getBoolean(RECURSIVE_INDEXING_KEY, this.RECURSIVE_INDEXING_DEFAULT);
    List<Path> inputPaths = new ArrayList<Path>();
    for (String strPath : args) {
      visitPath(new Path(strPath), nonTemporaryFilter, inputPaths, this.recursiveIndexing);
    }

    if (inputPaths.isEmpty()) {
      LOG.info("No input paths found - perhaps all " +
          ".lzo files have already been indexed.");
      return 0;
    }

    Job job = new Job(this.conf);
    job.setJobName("Distributed Lzo Indexer " + Arrays.toString(args));

    job.setOutputKeyClass(Path.class);
    job.setOutputValueClass(LongWritable.class);

    // The LzoIndexOutputFormat doesn't currently work with speculative execution.
    // Patches welcome.
    job.getConfiguration().setBoolean(
      "mapred.map.tasks.speculative.execution", false);

    job.setJarByClass(DistributedLzoIndexer.class);
    job.setInputFormatClass(LzoSplitInputFormat.class);
    job.setOutputFormatClass(LzoIndexOutputFormat.class);
    job.setNumReduceTasks(0);
    job.setMapperClass(Mapper.class);

    for (Path p : inputPaths) {
      FileInputFormat.addInputPath(job, p);
    }

    job.submit();

    LOG.info("Started DistributedIndexer " + job.getJobID() + " with " +
        inputPaths.size() + " splits for " + Arrays.toString(args));

    if (job.waitForCompletion(true)) {
      long successfulMappers = CompatibilityUtil.getCounterValue(
          job.getCounters().findCounter(LzoSplitRecordReader.Counters.READ_SUCCESS));

      if (successfulMappers == inputPaths.size()) {
        return 0;
      }

      // some of the mappers failed
      LOG.error("DistributedIndexer " + job.getJobID() + " failed. "
          + (inputPaths.size() - successfulMappers)
          + " out of " + inputPaths.size() + " mappers failed.");
    } else {
      LOG.error("DistributedIndexer job " + job.getJobID() + " failed.");
    }

    return 1; // failure
  }

  public static void main(String[] args) throws Exception {
    int exitCode = ToolRunner.run(new DistributedLzoIndexer(), args);
    System.exit(exitCode);
  }

  public void printUsage() {
    String usage =
        "Command: hadoop jar /path/to/this/jar com.hadoop.compression.lzo.DistributedLzoIndexer <file.lzo | directory> [file2.lzo directory3 ...]" +
        "\nConfiguration options: [values] <default> description" +
        "\n" + this.SKIP_INDEXING_SMALL_FILES_KEY + " [true,false] <" + this.SKIP_INDEXING_SMALL_FILES_DEFAULT + "> When indexing, skip files smaller than a block in size." +
        "\n" + this.RECURSIVE_INDEXING_KEY + " [true,false] <" + this.RECURSIVE_INDEXING_DEFAULT + "> Look for files to index recursively from paths on command line.";
    System.err.println(usage);
  }
}

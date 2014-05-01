/*
 * Copyright 2014 Continuuity,Inc. All Rights Reserved.
 */
package com.continuuity.data2.transaction.stream;

import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.conf.Constants;
import com.continuuity.common.io.Locations;
import com.continuuity.common.queue.QueueName;
import com.continuuity.data.stream.StreamFileOffset;
import com.continuuity.data.stream.StreamUtils;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Closeables;
import com.google.gson.Gson;
import org.apache.twill.filesystem.Location;
import org.apache.twill.filesystem.LocationFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * An abstract base {@link StreamAdmin} for File based stream.
 */
public abstract class AbstractStreamFileAdmin implements StreamAdmin {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractStreamFileAdmin.class);
  private static final String CONFIG_FILE_NAME = "config.json";
  private static final Gson GSON = new Gson();

  private final Location streamBaseLocation;
  private final CConfiguration cConf;
  private final StreamConsumerStateStoreFactory stateStoreFactory;

  protected AbstractStreamFileAdmin(LocationFactory locationFactory, CConfiguration cConf,
                                    StreamConsumerStateStoreFactory stateStoreFactory) {
    this.cConf = cConf;
    this.streamBaseLocation = locationFactory.create(cConf.get(Constants.Stream.BASE_DIR));
    this.stateStoreFactory = stateStoreFactory;
  }

  @Override
  public void dropAll() throws Exception {
    // TODO: How to support it properly with opened stream writer?
  }

  @Override
  public void configureInstances(QueueName name, long groupId, int instances) throws Exception {
    Preconditions.checkArgument(name.isStream(), "The {} is not stream.", name);
    Preconditions.checkArgument(instances > 0, "Number of consumer instances must be > 0.");

    LOG.info("Configure instances: {} {}", groupId, instances);

    StreamConfig config = StreamUtils.ensureExists(this, name.getSimpleName());
    StreamConsumerStateStore stateStore = stateStoreFactory.create(config);
    try {
      Set<StreamConsumerState> states = Sets.newHashSet();
      stateStore.getByGroup(groupId, states);

      Set<StreamConsumerState> newStates = Sets.newHashSet();
      Set<StreamConsumerState> removeStates = Sets.newHashSet();
      mutateStates(groupId, instances, states, newStates, removeStates);

      // Save the states back
      stateStore.save(newStates);
      stateStore.remove(removeStates);

      LOG.info("Configure instances new states: {} {} {}", groupId, instances, newStates);
      LOG.info("Configure instances remove states: {} {} {}", groupId, instances, removeStates);

    } finally {
      stateStore.close();
    }

  }

  @Override
  public void configureGroups(QueueName name, Map<Long, Integer> groupInfo) throws Exception {
    Preconditions.checkArgument(name.isStream(), "The {} is not stream.", name);
    Preconditions.checkArgument(!groupInfo.isEmpty(), "Consumer group information must not be empty.");

    LOG.info("Configure groups for {}: {}", name, groupInfo);

    StreamConfig config = StreamUtils.ensureExists(this, name.getSimpleName());
    StreamConsumerStateStore stateStore = stateStoreFactory.create(config);
    try {
      Set<StreamConsumerState> states = Sets.newHashSet();
      stateStore.getAll(states);

      // Remove all groups that are no longer exists. The offset information in that group can be discarded.
      Set<StreamConsumerState> removeStates = Sets.newHashSet();
      for (StreamConsumerState state : states) {
        if (!groupInfo.containsKey(state.getGroupId())) {
          removeStates.add(state);
        }
      }

      // For each groups, compute the new file offsets if needed
      Set<StreamConsumerState> newStates = Sets.newHashSet();
      for (Map.Entry<Long, Integer> entry : groupInfo.entrySet()) {
        final long groupId = entry.getKey();

        // Create a view of old states which match with the current groupId only.
        mutateStates(groupId, entry.getValue(), Sets.filter(states, new Predicate<StreamConsumerState>() {
          @Override
          public boolean apply(StreamConsumerState state) {
            return state.getGroupId() == groupId;
          }
        }), newStates, removeStates);
      }

      // Save the states back
      stateStore.save(newStates);
      stateStore.remove(removeStates);

      LOG.info("Configure groups new states: {} {}", groupInfo, newStates);
      LOG.info("Configure groups remove states: {} {}", groupInfo, removeStates);

    } finally {
      stateStore.close();
    }
  }

  @Override
  public void upgrade() throws Exception {
    // No-op
  }

  @Override
  public StreamConfig getConfig(String streamName) throws IOException {
    Location streamLocation = streamBaseLocation.append(streamName);
    Preconditions.checkArgument(streamLocation.isDirectory(), "Stream '{}' not exists.", streamName);

    Location configLocation = streamLocation.append(CONFIG_FILE_NAME);
    Reader reader = new InputStreamReader(configLocation.getInputStream(), Charsets.UTF_8);
    try {
      StreamConfig config = GSON.fromJson(reader, StreamConfig.class);
      return new StreamConfig(streamName, config.getPartitionDuration(), config.getIndexInterval(), streamLocation);
    } finally {
      Closeables.closeQuietly(reader);
    }
  }

  @Override
  public boolean exists(String name) throws Exception {
    try {
      return streamBaseLocation.append(name).append(CONFIG_FILE_NAME).exists();
    } catch (IOException e) {
      LOG.error("Exception when check for stream exist.", e);
      return false;
    }
  }

  @Override
  public void create(String name) throws Exception {
    create(name, null);
  }

  @Override
  public void create(String name, @Nullable Properties props) throws Exception {
    Location streamLocation = streamBaseLocation.append(name);
    if (!streamLocation.mkdirs() && !streamLocation.isDirectory()) {
      throw new IllegalStateException("Failed to create stream '" + name + "' at " + streamLocation.toURI());
    }

    Location configLocation = streamBaseLocation.append(name).append(CONFIG_FILE_NAME);
    if (!configLocation.createNew()) {
      // Stream already exists
      return;
    }

    Properties properties = (props == null) ? new Properties() : props;
    long partitionDuration = Long.parseLong(properties.getProperty(Constants.Stream.PARTITION_DURATION,
                                            cConf.get(Constants.Stream.PARTITION_DURATION)));
    long indexInterval = Long.parseLong(properties.getProperty(Constants.Stream.INDEX_INTERVAL,
                                                               cConf.get(Constants.Stream.INDEX_INTERVAL)));

    Location tmpConfigLocation = configLocation.getTempFile(null);
    StreamConfig config = new StreamConfig(name, partitionDuration, indexInterval, streamLocation);
    Writer writer = new OutputStreamWriter(tmpConfigLocation.getOutputStream(), Charsets.UTF_8);
    try {
      GSON.toJson(config, writer);
    } finally {
      writer.close();
    }
    tmpConfigLocation.renameTo(configLocation);
  }

  @Override
  public void truncate(String name) throws Exception {
    // TODO: How to support it properly with opened stream writer?
  }

  @Override
  public void drop(String name) throws Exception {
  // TODO: How to support it properly with opened stream writer?
  }

  @Override
  public void upgrade(String name, Properties properties) throws Exception {
    // No-op
  }

  private void mutateStates(long groupId, int instances, Set<StreamConsumerState> states,
                            Set<StreamConsumerState> newStates, Set<StreamConsumerState> removeStates) {
    int oldInstances = states.size();
    if (oldInstances == instances) {
      // If number of instances doesn't changed, no need to mutate any states
      return;
    }

    // Collects smallest offsets across all existing consumers
    // Map from event file location to file offset.
    // Use tree map to maintain ordering consistency in the offsets.
    // Not required by any logic, just easier to look at when logged.
    Map<Location, StreamFileOffset> fileOffsets = Maps.newTreeMap(Locations.LOCATION_COMPARATOR);

    for (StreamConsumerState state : states) {
      for (StreamFileOffset fileOffset : state.getState()) {
        StreamFileOffset smallestOffset = fileOffsets.get(fileOffset.getEventLocation());
        if (smallestOffset == null || fileOffset.getOffset() < smallestOffset.getOffset()) {
          fileOffsets.put(fileOffset.getEventLocation(), new StreamFileOffset(fileOffset));
        }
      }
    }

    // Constructs smallest offsets
    Collection<StreamFileOffset> smallestOffsets = fileOffsets.values();

    // When group size changed, reset all existing instances states to have smallest files offsets constructed above.
    for (StreamConsumerState state : states) {
      if (state.getInstanceId() < instances) {
        // Only keep valid instances
        newStates.add(new StreamConsumerState(groupId, state.getInstanceId(), smallestOffsets));
      } else {
        removeStates.add(state);
      }
    }

    // For all new instances, set files offsets to smallest one constructed above.
    for (int i = oldInstances; i < instances; i++) {
      newStates.add(new StreamConsumerState(groupId, i, smallestOffsets));
    }
  }
}

/**
 * Copyright 2014 Nirmata, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nirmata.workflow.queue.zookeeper;

import com.google.common.base.Preconditions;
import com.nirmata.workflow.models.ExecutableTask;
import com.nirmata.workflow.details.ZooKeeperConstants;
import com.nirmata.workflow.models.Task;
import com.nirmata.workflow.models.TaskType;
import com.nirmata.workflow.queue.Queue;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.DistributedDelayQueue;
import org.apache.curator.framework.recipes.queue.DistributedQueue;
import org.apache.curator.framework.recipes.queue.QueueBuilder;
import org.apache.curator.utils.CloseableUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZooKeeperQueue implements Queue
{
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final DistributedQueue<ExecutableTask> queue;
    private final DistributedDelayQueue<ExecutableTask> delayQueue;

    public ZooKeeperQueue(CuratorFramework curator, TaskType taskType)
    {
        curator = Preconditions.checkNotNull(curator, "curator cannot be null");
        String path = ZooKeeperConstants.getQueuePath(taskType);
        QueueBuilder<ExecutableTask> builder = QueueBuilder.builder(curator, null, new TaskQueueSerializer(), path);
        if ( taskType.isIdempotent() )
        {
            builder = builder.lockPath(ZooKeeperConstants.getQueuePath(taskType));
        }
        queue = taskType.hasDelay() ? null : builder.buildQueue();
        delayQueue = taskType.hasDelay() ? builder.buildDelayQueue() : null;
    }

    @Override
    public void put(ExecutableTask executableTask)
    {
        try
        {
            if ( queue != null )
            {
                queue.put(executableTask);
            }
            else
            {
                long delayUntilEpoch = 1;
                try
                {
                    String value = executableTask.getMetaData().get(Task.META_TASK_DELAY);
                    if ( value != null )
                    {
                        delayUntilEpoch = Long.parseLong(value);
                    }
                }
                catch ( NumberFormatException ignore )
                {
                    // ignore
                }
                delayQueue.put(executableTask, delayUntilEpoch);
            }
        }
        catch ( Exception e )
        {
            log.error("Could not add to queue for: " + executableTask, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void start()
    {
        try
        {
            if ( queue != null )
            {
                queue.start();
            }
            else
            {
                delayQueue.start();
            }
        }
        catch ( Exception e )
        {
            log.error("Could not start queue", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close()
    {
        CloseableUtils.closeQuietly(queue);
        CloseableUtils.closeQuietly(delayQueue);
    }
}

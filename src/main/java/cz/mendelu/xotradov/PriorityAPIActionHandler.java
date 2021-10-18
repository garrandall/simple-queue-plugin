/*
 * The MIT License
 *
 * TODO
 *
 * Copyright (c) 2013-2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package cz.mendelu.xotradov;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import com.cloudbees.workflow.util.ServeJson;
import com.google.common.annotations.VisibleForTesting;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Queue;
import hudson.model.queue.QueueSorter;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;

/** TODO
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Extension
@Restricted(NoExternalUse.class)
public class PriorityAPIActionHandler extends TransientActionFactory<Queue.Item> implements Action {
    public static final String URL_BASE = "priority";
    private static final Logger logger = Logger.getLogger(PriorityAPIActionHandler.class.getName());

    public Queue.Item target;

    @Override
    public String getIconFileName() {
        // No display
        return null;
    }

    @Override
    public String getDisplayName() {
        // No display
        return null;
    }

    @Override
    public String getUrlName() {
        return URL_BASE;
    }

    @Override
    public Class<Queue.Item> type() {
        return Queue.Item.class;
    }

    protected Queue.Item getItem() {
        return target;
    }

    @Nonnull
    @Override
    public Collection<? extends Action> createFor(@Nonnull Queue.Item target) {
        try {
            PriorityAPIActionHandler instance = getClass().newInstance();
            instance.target = target;
            return Collections.singleton(instance);
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Class '%s' does not implement a public default constructor.", getClass().getName()));
        }
    }

    /**
     * POST /queue/item/:id/priority/move
     * TODO: error responses should be json or xml - needs @ServesJSON?
     * @param moveType
     * @return
     * @throws IOException
     */
    @RequirePOST
    @ServeJson
    public HttpResponse doMove(@QueryParameter String moveType) throws IOException {
        Jenkins j;
        Queue queue;
        MoveType mt;

        if (target == null) {
            return HttpResponses.errorWithoutStack(404, "could not find item");
        }

        if ((j = Jenkins.getInstanceOrNull()) == null) {
            return HttpResponses.errorWithoutStack(500, "could not find jenkins"); // TODO
        }

        if (!j.hasPermission(PermissionHandler.SIMPLE_QUEUE_MOVE_PERMISSION)) {
            return HttpResponses.forbidden();
        }

        if ((queue = j.getQueue()) == null) {
            return HttpResponses.errorWithoutStack(500, "could not find queue");
        }

        try {
            mt = MoveType.valueOf(moveType);
        } catch (Exception e) {
            logger.info(e.getMessage());
            return HttpResponses.errorWithoutStack(400, "invalid moveType");
        }

        try {
            QueueSorter originalQueueSorter = queue.getSorter();
            if (originalQueueSorter == null) originalQueueSorter = new DefaultSorter();
            SimpleQueueSorter simpleQueueSorter = new SimpleQueueSorter(originalQueueSorter);
            queue.setSorter(simpleQueueSorter);

            move(queue, target, mt); // TODO
        } catch (Exception e) {
            logger.info(e.getMessage());
            return HttpResponses.errorWithoutStack(500, e.getMessage());
        }

        return HttpResponses.ok(); // TODO provide info?
    }

    private void move(@Nonnull Queue queue, @Nonnull Queue.Item item, @Nonnull MoveType moveType) {
        switch (moveType) {
            case UP_FAST:
                moveToTop(item,queue);
                break;
            case UP:
                moveUp(item,queue);
                break;
            case DOWN:
                moveDown(item,queue);
                break;
            case DOWN_FAST:
                moveToBottom(item,queue);
                break;
            case TOP:
            case BOTTOM:
                break;
        }
    }

    @VisibleForTesting
    public @CheckForNull Queue.Item getBottom(@Nonnull List<Queue.Item> queueItems) {
        if (queueItems.size() > 0) {
            return queueItems.get(queueItems.size() - 1);
        } else {
            return null;
        }
    }

    @VisibleForTesting
    public void putAOnTopOfB(@Nonnull Queue.Item itemA, @Nonnull Queue.Item itemB, @Nonnull Queue queue) {
        Queue.Item[] items = queue.getItems();
        List<Queue.Item> itemsC = getItemsBetween(itemA, itemB, items);
            QueueSorter queueSorter = queue.getSorter();
            if (queueSorter instanceof SimpleQueueSorter) {
                SimpleQueueComparator comparator = ((SimpleQueueSorter) queueSorter).getSimpleQueueComparator();
                comparator.addDesire(itemB.getId(), itemA.getId());
                for (Queue.Item itemC : itemsC) {
                    comparator.addDesire(itemC.getId(), itemA.getId());
                }
                resort(queue);
            }
    }

    private List<Queue.Item> getItemsBetween(Queue.Item itemA, Queue.Item itemB, Queue.Item[] items) {
        if (isABeforeB(itemA, itemB, items)) {
            return getItemsBetweenTopFirst(itemB,itemA,items);
        } else {
            return getItemsBetweenTopFirst(itemA,itemB,items);
        }
    }

    ///We suppose that both items are in the queue present
    private boolean isABeforeB(Queue.Item itemA, Queue.Item itemB, Queue.Item[] items) {
        List<Queue.Item> itemsBefore = getItemsBefore(itemA, items);
        for (Queue.Item item : itemsBefore) {
            if (item.getId() == itemB.getId()) {
                return false;
            }
        }
        return true;
    }


    private List<Queue.Item> getItemsBetweenTopFirst(Queue.Item topItem, Queue.Item bottomItem, Queue.Item[] items) {
        List<Queue.Item> returnList = new ArrayList<>();
        if (items.length > 2) {
            boolean seenBottom = false;
            boolean seenTop = false;
            for (Queue.Item item : items) {
                if (!seenTop) {
                    if (item.getId() == bottomItem.getId()) {
                        seenBottom = true;
                    }
                    if (seenBottom) {
                        if (item.getId() == topItem.getId()) {
                            seenTop = true;
                        } else {
                            returnList.add(item);
                        }
                    }
                }
            }
        }
        return returnList;
    }

    /**
     * @return Returns last item from collection, in queue it has the least priority
     */
    @VisibleForTesting
    public @CheckForNull Queue.Item getTop(Collection<Queue.Item> items) {
        int size = items.size();
        if (size > 0) {
            for (int i = size; i > 1; i--) {
                items.iterator().next();
            }
            return items.iterator().next();
        } else {
            return null;
        }
    }

    /**
     * @param itemA Item with least importance
     */
    @VisibleForTesting
    public void moveToTop(@Nonnull Queue.Item itemA,@Nonnull Queue queue) {
        Queue.Item[] items = queue.getItems();
        List<Queue.Item> itemsB = getItemsBefore(itemA, items);
        if (itemsB.size() != 0) {
            QueueSorter queueSorter = queue.getSorter();
            if (queueSorter instanceof SimpleQueueSorter) {
                SimpleQueueComparator comparator = ((SimpleQueueSorter) queueSorter).getSimpleQueueComparator();
                for (Queue.Item itemB : itemsB) {
                    comparator.addDesire(itemB.getId(), itemA.getId());
                }
                resort(queue);
            }
        }
    }

    /**
      @param itemA Item to be moved up in list = more away from execution
     */
    @VisibleForTesting
    public void moveUp(Queue.Item itemA, Queue queue) {
        Queue.Item[] items = queue.getItems();
        Queue.Item itemB = getItemBefore(itemA, items);
        if (itemB != null) {
            QueueSorter queueSorter = queue.getSorter();
            if (queueSorter instanceof SimpleQueueSorter) {
                ((SimpleQueueSorter) queueSorter).getSimpleQueueComparator().addDesire(itemB.getId(), itemA.getId());
                resort(queue);
            }
        }
    }

    @VisibleForTesting
    public void moveDown(Queue.Item itemA, Queue queue) {
        Queue.Item[] items = queue.getItems();
        Queue.Item itemB = getItemAfter(itemA, items);
        if (itemB != null) {
            QueueSorter queueSorter = queue.getSorter();
            if (queueSorter instanceof SimpleQueueSorter) {
                ((SimpleQueueSorter) queueSorter).getSimpleQueueComparator().addDesire(itemA.getId(), itemB.getId());
                resort(queue);
            }
        }
    }

    /**
     * @param itemA The most important item
     * */
    @VisibleForTesting
    public void moveToBottom(@Nonnull Queue.Item itemA, @Nonnull Queue queue) {
        Queue.Item[] items = queue.getItems();
        List<Queue.Item> itemsB = getItemsAfter(itemA, items);
        if (itemsB.size() != 0) {
            QueueSorter queueSorter = queue.getSorter();
            if (queueSorter instanceof SimpleQueueSorter) {
                SimpleQueueComparator comparator = ((SimpleQueueSorter) queueSorter).getSimpleQueueComparator();
                for (Queue.Item itemB : itemsB) {
                    comparator.addDesire(itemA.getId(), itemB.getId());
                }
                resort(queue);
            }
        }
    }

    @Nonnull
    private List<Queue.Item> getItemsBefore(@Nonnull Queue.Item itemA, @Nonnull Queue.Item[] items) {
        List<Queue.Item> returnList = new ArrayList<>();
        if (items.length >= 2) {
            boolean seenItemA = false;
            for (Queue.Item item : items) {
                if (!seenItemA) {
                    if (item.getId() == itemA.getId()) {
                        seenItemA = true;
                    } else {
                        returnList.add(item);
                    }
                }
            }
        }
        return returnList;
    }

    @Nonnull
    private List<Queue.Item> getItemsAfter(@Nonnull Queue.Item itemA, @Nonnull Queue.Item[] items) {
        List<Queue.Item> returnList = new ArrayList<>();
        if (items.length >= 2) {
            boolean seenItemA = false;
            for (Queue.Item item : items) {
                if (seenItemA) {
                    // add item
                    returnList.add(item);
                } else {
                    // check for item
                    if (item.getId() == itemA.getId()) seenItemA = true;
                }
            }
        }
        return returnList;
    }

    /**
     * @param itemA Item after which should be returned item that has lower priority
     * @param items Has on [0] the top item, with the lowest priority
     * @return Returns item that is after in the queue order = the with higher priority = goes before to execution
     */
    @CheckForNull
    private Queue.Item getItemAfter(@Nonnull Queue.Item itemA, @Nonnull Queue.Item[] items) {
        if (items.length >= 2) {
            Queue.Item previous = null;
            for (Queue.Item itemB : items) {
                if ((previous != null) && (previous.getId() == itemA.getId())) {
                    return itemB;
                }
                previous = itemB;
            }
        }
        return null;
    }

    @CheckForNull
    private Queue.Item getItemBefore(Queue.Item itemA, Queue.Item[] items) {
        if (items.length >= 2) {
            Queue.Item itemB = null;
            for (Queue.Item itemFor : items) {
                if (itemFor.getId() == itemA.getId()) {
                    return itemB;
                }
                itemB = itemFor;
            }
        }
        return null;
    }

    private void resort(Queue queue) {
        queue.getSorter().sortBuildableItems(queue.getBuildableItems());
    }
}
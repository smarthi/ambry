package com.github.ambry.coordinator;

import com.github.ambry.clustermap.PartitionId;
import com.github.ambry.clustermap.ReplicaId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

import static java.lang.Math.min;

/**
 * An OperationPolicy controls parallelism of an operation (how many requests in flight at a time), probing policy
 * (order in which to send requests to replicas), and whether an operation isComplete or not.
 */
public interface OperationPolicy {
  /**
   * Determines if more requests should be sent.
   *
   * @param requestsInFlight replica IDs to which a request is currently in flight.
   * @return true iff one or more additional requests should be in flight
   */
  public boolean sendMoreRequests(Collection<ReplicaId> requestsInFlight);

  /**
   * Determines if an operation is now successfully complete.
   *
   * @return true iff the operation is successfully complete.
   */
  public boolean isComplete();

  /**
   * Determines if an operation may complete in the future.
   *
   * @return true iff the operation may complete in the future, false if the operation can never complete in the
   *         future.
   */
  public boolean mayComplete();

  /**
   * Accounts for successful request-response pairs. Operation must invoke this method so that sendMoreRequests and
   * isComplete has necessary information.
   *
   * @param replicaId ReplicaId that successfully handled request.
   */
  public void onSuccessfulResponse(ReplicaId replicaId);

  /**
   * Accounts for failed request-response pairs. Operation must invoke this method so that sendMoreRequests and
   * isComplete has necessary information. A failed request-response pair is any request-response pair that experienced
   * an exception or that returned are response with an error.
   *
   * @param replicaId ReplicaId that did not successfully handle request.
   */
  public void onFailedResponse(ReplicaId replicaId);

  /**
   * Determines the next replica to which to send a request. This method embodies the "probing" policy for the
   * operation. I.e., which replicas are sent requests when.
   *
   * @return ReplicaId of next replica to send to.
   */
  public ReplicaId getNextReplicaIdForSend();

  /**
   * Returns the count of replica ids in the partition to which the specified blob id belongs.
   *
   * @return count of replica Ids in partition.
   */
  public int getReplicaIdCount();
}

/**
 * Implements a local datacenter first probing policy. I.e., replicas for the local datacenter are sent requests before
 * replicas in remote datacenters. Also implements basic request accounting of failed and successful requests.
 */
abstract class ProbeLocalFirstOperationPolicy implements OperationPolicy {
  protected int replicaIdCount;
  protected Queue<ReplicaId> orderedReplicaIds;

  protected List<ReplicaId> failedRequests;
  protected List<ReplicaId> successfulRequests;

  private Logger logger = LoggerFactory.getLogger(getClass());

  protected ProbeLocalFirstOperationPolicy(String datacenterName, PartitionId partitionId) throws CoordinatorException {
    this.replicaIdCount = partitionId.getReplicaIds().size();
    if (replicaIdCount < 1) {
      logger.error("PartitionId {} has invalid number of replicas: {}.", partitionId, replicaIdCount);
      throw new CoordinatorException("Partition has invalid configuration.", CoordinatorError.UnexpectedInternalError);
    }
    this.orderedReplicaIds = orderReplicaIds(datacenterName, partitionId.getReplicaIds());

    this.failedRequests = new ArrayList<ReplicaId>(replicaIdCount);
    this.successfulRequests = new ArrayList<ReplicaId>(replicaIdCount);
  }

  protected Queue<ReplicaId> orderReplicaIds(String datacenterName, List<ReplicaId> replicaIds) {
    Queue<ReplicaId> orderedReplicaIds = new ArrayDeque<ReplicaId>(replicaIdCount);

    List<ReplicaId> localReplicaIds = new ArrayList<ReplicaId>(replicaIdCount);
    List<ReplicaId> remoteReplicaIds = new ArrayList<ReplicaId>(replicaIdCount);
    for (ReplicaId replicaId : replicaIds) {
      if (replicaId.getDataNodeId().getDatacenterName().equals(datacenterName)) {
        localReplicaIds.add(replicaId);
      }
      else {
        remoteReplicaIds.add(replicaId);
      }
    }

    Collections.shuffle(localReplicaIds);
    orderedReplicaIds.addAll(localReplicaIds);
    Collections.shuffle(remoteReplicaIds);
    orderedReplicaIds.addAll(remoteReplicaIds);

    return orderedReplicaIds;
  }

  @Override
  public abstract boolean sendMoreRequests(Collection<ReplicaId> requestsInFlight);

  @Override
  public abstract boolean isComplete();

  @Override
  public void onSuccessfulResponse(ReplicaId replicaId) {
    successfulRequests.add(replicaId);
  }

  @Override
  public void onFailedResponse(ReplicaId replicaId) {
    failedRequests.add(replicaId);
  }

  @Override
  public ReplicaId getNextReplicaIdForSend() {
    return orderedReplicaIds.remove();
  }

  @Override
  public int getReplicaIdCount() {
    return replicaIdCount;
  }
}

/**
 * Serially probes data nodes until blob is retrieved.
 */
class GetPolicy extends ProbeLocalFirstOperationPolicy {
  public GetPolicy(String datacenterName, PartitionId partitionId) throws CoordinatorException {
    super(datacenterName, partitionId);
  }

  @Override
  public boolean sendMoreRequests(Collection<ReplicaId> requestsInFlight) {
    return !orderedReplicaIds.isEmpty() && requestsInFlight.size() < 1;
  }

  @Override
  public boolean isComplete() {
    if (successfulRequests.size() >= 1) {
      return true;
    }
    return false;
  }

  @Override
  public boolean mayComplete() {
    if (failedRequests.size() == replicaIdCount) {
      return false;
    }
    return true;
  }
}

/**
 * Sends some number of requests in parallel and waits for a threshold number of successes.
 * <p/>
 * If "additional" parallelism is initially requested (i.e., more than success threshold), then best effort to keep
 * "additional" request outstanding in the face of failed requests. If parallelism is less than success threshold, then
 * no "additional" requests are kept in flight.
 */
abstract class ParallelOperationPolicy extends ProbeLocalFirstOperationPolicy {
  protected int successTarget;
  protected int requestParallelism;

  protected ParallelOperationPolicy(String datacenterName, PartitionId partitionId) throws CoordinatorException {
    super(datacenterName, partitionId);
  }

  @Override
  public boolean sendMoreRequests(Collection<ReplicaId> requestsInFlight) {
    if (orderedReplicaIds.isEmpty()) {
      return false;
    }

    int inFlightTarget;
    if (requestParallelism >= successTarget) {
      inFlightTarget = requestParallelism - successfulRequests.size();
    }
    else {
      inFlightTarget = min(requestParallelism, successTarget - successfulRequests.size());
    }
    return (requestsInFlight.size() < inFlightTarget);
  }

  @Override
  public boolean isComplete() {
    if (successfulRequests.size() >= successTarget) {
      return true;
    }
    return false;
  }

  @Override
  public boolean mayComplete() {
    if ((replicaIdCount - failedRequests.size()) < successTarget) {
      return false;
    }
    return true;
  }
}

/**
 * Sends requests in parallel --- threshold number for durability plus one for good luck. Durability threshold is 2 so
 * long as there are more than 2 replicas in the partition.
 */
class PutPolicy extends ParallelOperationPolicy {
  /*
   There are many possibilities for extending the put policy. Some ideas that have been discussed include the following:

   (1) Try a new partition ("slipping the partition") before trying remote replicas for initial partition. This
   may be faster than requiring WAN put before succeeding.

   (2) sending additional put requests (increasing the requestParallelism) after a short timeout.
  */
  public PutPolicy(String datacenterName, PartitionId partitionId) throws CoordinatorException {
    super(datacenterName, partitionId);
    if (replicaIdCount == 1) {
      super.successTarget = 1;
      super.requestParallelism = 1;
    }
    else if (replicaIdCount <= 2) {
      super.successTarget = 1;
      super.requestParallelism = 2;
    }
    else {
      super.successTarget = 2;
      super.requestParallelism = 3;
    }
  }
}

/**
 * Sends requests in parallel to all replicas. Durability threshold is 2, so  long as there are more than 2 replicas in
 * the partition. Policy is used for both delete and cancelTTL.
 */
class AllInParallelOperationPolicy extends ParallelOperationPolicy {
  public AllInParallelOperationPolicy(String datacenterName, PartitionId partitionId) throws CoordinatorException {
    super(datacenterName, partitionId);
    if (replicaIdCount == 1) {
      super.successTarget = 1;
      super.requestParallelism = 1;
    }
    else if (replicaIdCount <= 2) {
      super.successTarget = 1;
      super.requestParallelism = 2;
    }
    else {
      super.successTarget = 2;
      super.requestParallelism = replicaIdCount;
    }
  }
}

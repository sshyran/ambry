/*
 * Copyright 2018 LinkedIn Corp. All rights reserved.
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
 */

package com.github.ambry.router;

import com.github.ambry.account.Account;
import com.github.ambry.account.AccountService;
import com.github.ambry.account.Container;
import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.Callback;
import com.github.ambry.commons.ResponseHandler;
import com.github.ambry.config.RouterConfig;
import com.github.ambry.network.RequestInfo;
import com.github.ambry.network.ResponseInfo;
import com.github.ambry.notification.NotificationSystem;
import com.github.ambry.protocol.TtlUpdateResponse;
import com.github.ambry.quota.QuotaChargeCallback;
import com.github.ambry.utils.Pair;
import com.github.ambry.utils.Time;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Handles {@link TtlUpdateOperation}. A {@code TtlUpdateManager} keeps track of all the TTL update
 * operations that are assigned to it, and manages their states and life cycles.
 */
class TtlUpdateManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(TtlUpdateManager.class);
  private final ClusterMap clusterMap;
  private final NotificationSystem notificationSystem;
  private final Time time;
  private final ResponseHandler responseHandler;
  private final AccountService accountService;
  private final NonBlockingRouterMetrics routerMetrics;
  private final RouterConfig routerConfig;
  private final Set<TtlUpdateOperation> ttlUpdateOperations = ConcurrentHashMap.newKeySet();
  private final Map<Integer, TtlUpdateOperation> correlationIdToTtlUpdateOperation = new HashMap<>();
  private final RequestRegistrationCallback<TtlUpdateOperation> requestRegistrationCallback =
      new RequestRegistrationCallback<>(correlationIdToTtlUpdateOperation);

  /**
   * Creates a TtlUpdateManager.
   * @param clusterMap The {@link ClusterMap} of the cluster.
   * @param responseHandler The {@link ResponseHandler} used to notify failures for failure detection.
   * @param notificationSystem The {@link NotificationSystem} used for notifying ttl updates for blobs.
   * @param accountService The {@link AccountService} used for account/container id and name mapping.
   * @param routerConfig The {@link RouterConfig} containing the configs for the TtlUpdateManager.
   * @param routerMetrics The {@link NonBlockingRouterMetrics} to be used for reporting metrics.
   * @param time The {@link Time} instance to use.
   */
  TtlUpdateManager(ClusterMap clusterMap, ResponseHandler responseHandler, NotificationSystem notificationSystem,
      AccountService accountService, RouterConfig routerConfig, NonBlockingRouterMetrics routerMetrics, Time time) {
    this.clusterMap = clusterMap;
    this.responseHandler = responseHandler;
    this.accountService = accountService;
    this.notificationSystem = notificationSystem;
    this.routerConfig = routerConfig;
    this.routerMetrics = routerMetrics;
    this.time = time;
  }

  /**
   * Submits {@link TtlUpdateOperation}(s) to this {@link TtlUpdateManager}.
   * @param blobIdStrs The original blobId strings
   * @param serviceId The service ID of the service updating the ttl of the blob(s). This can be null if unknown.
   * @param expiresAtMs The new expiry time (in ms) of the blob.
   * @param futureResult The {@link FutureResult} that will contain the result eventually and exception if any.
   * @param callback The {@link Callback} that will be called on completion of the request.
   * @param quotaChargeCallback {@link QuotaChargeCallback} object to account for quota.
   * @throws RouterException if the blobIdStr is invalid.
   */
  void submitTtlUpdateOperation(Collection<String> blobIdStrs, String serviceId, long expiresAtMs,
      FutureResult<Void> futureResult, Callback<Void> callback, QuotaChargeCallback quotaChargeCallback)
      throws RouterException {
    List<BlobId> blobIds = new ArrayList<>();
    for (String blobIdStr : blobIdStrs) {
      BlobId blobId = RouterUtils.getBlobIdFromString(blobIdStr, clusterMap);
      if (blobId.getDatacenterId() != ClusterMap.UNKNOWN_DATACENTER_ID
          && blobId.getDatacenterId() != clusterMap.getLocalDatacenterId()) {
        routerMetrics.ttlUpdateBlobNotOriginateLocalOperationRate.mark();
      }
      blobIds.add(blobId);
    }
    if (blobIds.size() == 1) {
      TtlUpdateOperation ttlUpdateOperation =
          new TtlUpdateOperation(clusterMap, routerConfig, routerMetrics, blobIds.get(0), serviceId, expiresAtMs,
              time.milliseconds(), callback, time, futureResult, quotaChargeCallback);
      ttlUpdateOperations.add(ttlUpdateOperation);
    } else {
      BatchOperationCallbackTracker tracker =
          new BatchOperationCallbackTracker(blobIds, futureResult, callback, quotaChargeCallback);
      long operationTimeMs = time.milliseconds();
      for (BlobId blobId : blobIds) {
        TtlUpdateOperation ttlUpdateOperation =
            new TtlUpdateOperation(clusterMap, routerConfig, routerMetrics, blobId, serviceId, expiresAtMs,
                operationTimeMs, tracker.getCallback(blobId), time, BatchOperationCallbackTracker.DUMMY_FUTURE,
                quotaChargeCallback);
        ttlUpdateOperations.add(ttlUpdateOperation);
      }
    }
  }

  /**
   * Polls all ttl update operations and populates a list of {@link RequestInfo} to be sent to data nodes in order to
   * complete ttl update operations.
   * @param requestsToSend list to be filled with the requests created.
   * @param requestsToDrop list to be filled with the requests to drop.
   */
  void poll(List<RequestInfo> requestsToSend, Set<Integer> requestsToDrop) {
    long startTime = time.milliseconds();
    requestRegistrationCallback.setRequestsToSend(requestsToSend);
    requestRegistrationCallback.setRequestsToDrop(requestsToDrop);
    for (TtlUpdateOperation op : ttlUpdateOperations) {
      boolean exceptionEncountered = false;
      try {
        op.poll(requestRegistrationCallback);
      } catch (Exception e) {
        exceptionEncountered = true;
        op.setOperationException(
            new RouterException("TTL poll encountered unexpected error", e, RouterErrorCode.UnexpectedInternalError));
      }
      if (exceptionEncountered || op.isOperationComplete()) {
        if (ttlUpdateOperations.remove(op)) {
          // In order to ensure that an operation is completed only once, call onComplete() only at the place where the
          // operation actually gets removed from the set of operations. See comment within close().
          onComplete(op);
        }
      }
    }
    routerMetrics.ttlUpdateManagerPollTimeMs.update(time.milliseconds() - startTime);
  }

  /**
   * Handles responses received for each of the {@link TtlUpdateOperation} within this TtlUpdateManager.
   * @param responseInfo the {@link ResponseInfo} containing the response.
   */
  void handleResponse(ResponseInfo responseInfo) {
    long startTime = time.milliseconds();
    TtlUpdateResponse ttlUpdateResponse =
        RouterUtils.extractResponseAndNotifyResponseHandler(responseHandler, routerMetrics, responseInfo,
            TtlUpdateResponse::readFrom, TtlUpdateResponse::getError);
    RequestInfo routerRequestInfo = responseInfo.getRequestInfo();
    int correlationId = routerRequestInfo.getRequest().getCorrelationId();
    TtlUpdateOperation ttlUpdateOperation = correlationIdToTtlUpdateOperation.remove(correlationId);
    if (ttlUpdateOperation == null) {
      LOGGER.warn("No TtlUpdateOperation found for correlation id: {}", correlationId);
      routerMetrics.ignoredResponseCount.inc();
      return;
    }
    // If it is still an active operation, hand over the response. Otherwise, ignore.
    if (ttlUpdateOperations.contains(ttlUpdateOperation)) {
      boolean exceptionEncountered = false;
      try {
        ttlUpdateOperation.handleResponse(responseInfo, ttlUpdateResponse);
      } catch (Exception e) {
        exceptionEncountered = true;
        ttlUpdateOperation.setOperationException(
            new RouterException("TTLUpdate handleResponse encountered unexpected error", e,
                RouterErrorCode.UnexpectedInternalError));
      }
      if (exceptionEncountered || ttlUpdateOperation.isOperationComplete()) {
        if (ttlUpdateOperations.remove(ttlUpdateOperation)) {
          onComplete(ttlUpdateOperation);
        }
      }
      routerMetrics.ttlUpdateManagerHandleResponseTimeMs.update(time.milliseconds() - startTime);
    } else {
      routerMetrics.ignoredResponseCount.inc();
    }
  }

  /**
   * Called when the ttl update operation is completed. The {@link TtlUpdateManager} also finishes the ttl update
   * operation by performing the callback and notification.
   * @param op The {@link TtlUpdateOperation} that has completed.
   */
  private void onComplete(TtlUpdateOperation op) {
    Exception e = op.getOperationException();
    if (e == null) {
      BlobId blobId = op.getBlobId();
      Pair<Account, Container> accountContainer =
          RouterUtils.getAccountContainer(accountService, blobId.getAccountId(), blobId.getContainerId());
      notificationSystem.onBlobTtlUpdated(op.getBlobId().getID(), op.getServiceId(), op.getExpiresAtMs(),
          accountContainer.getFirst(), accountContainer.getSecond());
    } else {
      routerMetrics.onUpdateBlobTtlError(e);
    }
    routerMetrics.operationDequeuingRate.mark();
    routerMetrics.updateBlobTtlOperationLatencyMs.update(time.milliseconds() - op.getOperationTimeMs());
    NonBlockingRouter.completeOperation(op.getFutureResult(), op.getCallback(), op.getOperationResult(),
        op.getOperationException());
  }

  /**
   * Closes the {@link TtlUpdateManager}. A {@link TtlUpdateManager} can be closed for only once. Any further close action
   * will have no effect.
   */
  void close() {
    for (TtlUpdateOperation op : ttlUpdateOperations) {
      // There is a rare scenario where the operation gets removed from this set and gets completed concurrently by
      // the RequestResponseHandler thread when it is in poll() or handleResponse(). In order to avoid the completion
      // from happening twice, complete it here only if the remove was successful.
      if (ttlUpdateOperations.remove(op)) {
        Exception e = new RouterException("Aborted operation because Router is closed.", RouterErrorCode.RouterClosed);
        routerMetrics.operationDequeuingRate.mark();
        routerMetrics.operationAbortCount.inc();
        routerMetrics.onUpdateBlobTtlError(e);
        NonBlockingRouter.completeOperation(op.getFutureResult(), op.getCallback(), null, e);
      }
    }
  }
}


package com.eucalyptus.tests.awssdk;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.ListDeadLetterSourceQueuesRequest;
import com.amazonaws.services.sqs.model.ListQueuesResult;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.eucalyptus.tests.awssdk.N4j.*;

/**
 * Created by ethomas on 9/21/16.
 */
public class TestSQSListDeadLetterSourceQueues {

  private static String account;
  private static String otherAccount;

  private static AmazonSQS accountSQSClient;
  private static AmazonSQS otherAccountSQSClient;

  @BeforeClass
  public static void init() throws Exception {
    print("### PRE SUITE SETUP - " + TestSQSListDeadLetterSourceQueues.class.getSimpleName());

    try {
      getCloudInfoAndSqs();
      account = "sqs-account-ldl-a-" + System.currentTimeMillis();
      synchronizedCreateAccount(account);
      accountSQSClient = getSqsClientWithNewAccount(account, "admin");
      otherAccount = "sqs-account-ldl-b-" + System.currentTimeMillis();
      synchronizedCreateAccount(otherAccount);
      otherAccountSQSClient = getSqsClientWithNewAccount(otherAccount, "admin");
    } catch (Exception e) {
      try {
        teardown();
      } catch (Exception ignore) {
      }
      throw e;
    }
  }

  @AfterClass
  public static void teardown() {
    print("### POST SUITE CLEANUP - " + TestSQSListDeadLetterSourceQueues.class.getSimpleName());
    if (account != null) {
      if (accountSQSClient != null) {
        ListQueuesResult listQueuesResult = accountSQSClient.listQueues();
        if (listQueuesResult != null) {
          listQueuesResult.getQueueUrls().forEach(accountSQSClient::deleteQueue);
        }
      }
      synchronizedDeleteAccount(account);
    }
    if (otherAccount != null) {
      if (otherAccountSQSClient != null) {
        ListQueuesResult listQueuesResult = otherAccountSQSClient.listQueues();
        if (listQueuesResult != null) {
          listQueuesResult.getQueueUrls().forEach(otherAccountSQSClient::deleteQueue);
        }
      }
      synchronizedDeleteAccount(otherAccount);
    }
  }

  @Test
  public void testListDeadLetterSourceQueuesOtherAccount() {
    testInfo(this.getClass().getSimpleName() + " - testListDeadLetterSourceQueuesOtherAccount");
    String queueName = "queue_name_list_dl_source_queues_other_account";
    String queueUrl = accountSQSClient.createQueue(queueName).getQueueUrl();
    String otherAccountQueueUrl = otherAccountSQSClient.createQueue(queueName).getQueueUrl();
    try {
      ListDeadLetterSourceQueuesRequest listDeadLetterSourceQueuesRequest = new ListDeadLetterSourceQueuesRequest();
      listDeadLetterSourceQueuesRequest.setQueueUrl(otherAccountQueueUrl);
      accountSQSClient.listDeadLetterSourceQueues(listDeadLetterSourceQueuesRequest);
      assertThat(false, "Should fail listing dead letter source queues for queue on different account");
    } catch (AmazonServiceException e) {
      assertThat(e.getStatusCode() == 403, "Correctly fail listing dead letter source queues for queue on different account");
    }
  }

  @Test
  public void testListDeadLetterSourceQueuesNonExistentAccount() throws Exception {
    testInfo(this.getClass().getSimpleName() + " - testListDeadLetterSourceQueuesNonExistentAccount");
    String queueName = "queue_name_list_dl_source_queues_nonexistent_account";

    String queueUrl = accountSQSClient.createQueue(queueName).getQueueUrl();
    List<String> pathParts = Lists.newArrayList(Splitter.on('/').omitEmptyStrings().split(new URL(queueUrl).getPath()));
    String accountId = pathParts.get(0);

    try {
      ListDeadLetterSourceQueuesRequest listDeadLetterSourceQueuesRequest = new ListDeadLetterSourceQueuesRequest();
      listDeadLetterSourceQueuesRequest.setQueueUrl(queueUrl.replace(accountId, "000000000000"));
      accountSQSClient.listDeadLetterSourceQueues(listDeadLetterSourceQueuesRequest);
      assertThat(false, "Should fail listing dead letter source queues for queue from non-existent user");
    } catch (AmazonServiceException e) {
      assertThat(e.getStatusCode() == 404, "Correctly fail listing dead letter source queues for queue from non-existent user");
    }
  }

  @Test
  public void testListDeadLetterSourceQueuesNonExistentQueue() {
    testInfo(this.getClass().getSimpleName() + " - testListDeadLetterSourceQueuesNonExistentQueue");
    String queueName = "queue_name_list_dl_source_queues_nonexistent_queue";
    String queueUrl = accountSQSClient.createQueue(queueName).getQueueUrl();
    try {
      ListDeadLetterSourceQueuesRequest listDeadLetterSourceQueuesRequest = new ListDeadLetterSourceQueuesRequest();
      listDeadLetterSourceQueuesRequest.setQueueUrl(queueUrl + "-bogus");
      accountSQSClient.listDeadLetterSourceQueues(listDeadLetterSourceQueuesRequest);
      assertThat(false, "Should fail listing dead letter source queues for queue from non-existent queue");
    } catch (AmazonServiceException e) {
      assertThat(e.getStatusCode() == 400, "Correctly fail listing dead letter source queues for queue from non-existent queue");
    }
  }

  @Test
  public void testListDeadLetterSourceQueuesRandomSample() {
    testInfo(this.getClass().getSimpleName() + " - testListDeadLetterSourceQueuesRandomSample");
    List<String> queueUrls = Lists.newArrayList();
    List<String> queueArns = Lists.newArrayList();
    List<Set<String>> deadLetterSourceQueues = Lists.newArrayList();
    int NUM_QUEUES = 5;
    int NUM_TRIALS = 25;
    for (int i=0 ; i < NUM_QUEUES ;i++) {
      queueUrls.add(accountSQSClient.createQueue("test_dl_random_" + i).getQueueUrl());
      queueArns.add(accountSQSClient.getQueueAttributes(queueUrls.get(i), Collections.singletonList("All")).getAttributes().get("QueueArn"));
      deadLetterSourceQueues.add(Sets.newHashSet());
    }

    Random random = new Random();
    for (int trials = 0; trials < NUM_TRIALS; trials++) {
      for (int i = 0; i < NUM_QUEUES; i++) {
        deadLetterSourceQueues.get(i).clear();
        accountSQSClient.setQueueAttributes(queueUrls.get(i), ImmutableMap.of("RedrivePolicy", ""));
      }
      for (int i = 0; i < NUM_QUEUES; i++) {
        int deadLetterQueueNum = random.nextInt(NUM_QUEUES + 1);
        if (deadLetterQueueNum == NUM_QUEUES) continue;
        String redrivePolicy = "{\"maxReceiveCount\":\"5\",\"deadLetterTargetArn\":\"" + queueArns.get(deadLetterQueueNum) + "\"}";
        queueArns.add(accountSQSClient.getQueueAttributes(queueUrls.get(i), Collections.singletonList("All")).getAttributes().get("QueueArn"));
        deadLetterSourceQueues.get(deadLetterQueueNum).add(queueUrls.get(i));
        accountSQSClient.setQueueAttributes(queueUrls.get(i), ImmutableMap.of("RedrivePolicy", redrivePolicy));
      }
      for (int i = 0; i < NUM_QUEUES; i++) {
        ListDeadLetterSourceQueuesRequest x = new ListDeadLetterSourceQueuesRequest();
        x.setQueueUrl(queueUrls.get(i));
        Set<String> actualDeadLetterSourceQueues =
          Sets.newHashSet(accountSQSClient.listDeadLetterSourceQueues(x).getQueueUrls());
        assertThat(actualDeadLetterSourceQueues.equals(deadLetterSourceQueues.get(i)), "Queues should match");
      }
    }
  }

}

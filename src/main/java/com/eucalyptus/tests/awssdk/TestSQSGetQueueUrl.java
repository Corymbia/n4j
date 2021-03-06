package com.eucalyptus.tests.awssdk;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.GetQueueUrlRequest;
import com.amazonaws.services.sqs.model.ListQueuesResult;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URL;
import java.util.List;

import static com.eucalyptus.tests.awssdk.N4j.*;

/**
 * Created by ethomas on 9/21/16.
 */
public class TestSQSGetQueueUrl {

  private static String account;
  private static String otherAccount;

  private static AmazonSQS accountSQSClient;
  private static AmazonSQS otherAccountSQSClient;

  @BeforeClass
  public static void init() throws Exception {
    print("### PRE SUITE SETUP - " + TestSQSGetQueueUrl.class.getSimpleName());

    try {
      getCloudInfoAndSqs();
      account = "sqs-account-gqu-a-" + System.currentTimeMillis();
      synchronizedCreateAccount(account);
      accountSQSClient = getSqsClientWithNewAccount(account, "admin");
      otherAccount = "sqs-account-gqu-b-" + System.currentTimeMillis();
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
    print("### POST SUITE CLEANUP - " + TestSQSGetQueueUrl.class.getSimpleName());
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
  public void testGetQueueUrlOtherAccount() throws Exception {
    testInfo(this.getClass().getSimpleName() + " - testGetQueueUrlOtherAccount");
    String queueName = "queue_name_get_queue_url_other_account";
    String queueUrl = accountSQSClient.createQueue(queueName).getQueueUrl();
    String otherAccountQueueUrl = otherAccountSQSClient.createQueue(queueName).getQueueUrl();
    List<String> pathParts = Lists.newArrayList(Splitter.on('/').omitEmptyStrings().split(new URL(otherAccountQueueUrl).getPath()));
    String otherAccountId = pathParts.get(0);

    try {
      GetQueueUrlRequest getQueueUrlRequest = new GetQueueUrlRequest();
      getQueueUrlRequest.setQueueName(queueName);
      getQueueUrlRequest.setQueueOwnerAWSAccountId(otherAccountId);
      accountSQSClient.getQueueUrl(getQueueUrlRequest);
      assertThat(false, "Should fail getting queue url on different account");
    } catch (AmazonServiceException e) {
      assertThat(e.getStatusCode() == 400, "Correctly fail getting queue url on different account");
    }
  }

  @Test
  public void testGetQueueUrlNonExistentAccount() {
    testInfo(this.getClass().getSimpleName() + " - testGetQueueUrlNonExistentAccount");
    String queueName = "queue_name_get_queue_url_nonexistent_account";

    String queueUrl = accountSQSClient.createQueue(queueName).getQueueUrl();

    try {
      GetQueueUrlRequest getQueueUrlRequest = new GetQueueUrlRequest();
      getQueueUrlRequest.setQueueName(queueName);
      getQueueUrlRequest.setQueueOwnerAWSAccountId("000000000000");
      accountSQSClient.getQueueUrl(getQueueUrlRequest);
      assertThat(false, "Should fail deleting queue non-existent user");
    } catch (AmazonServiceException e) {
      assertThat(e.getStatusCode() == 404, "Correctly fail getting queue url from a non-existent user");
    }
  }

  @Test
  public void testGetQueueUrlNonExistentQueue() {
    testInfo(this.getClass().getSimpleName() + " - testGetQueueUrlNonExistentQueue");
    String queueName = "queue_name_get_queue_url_nonexistent_queue";
    String queueUrl = accountSQSClient.createQueue(queueName).getQueueUrl();
    try {
      accountSQSClient.getQueueUrl(queueName + "-bogus");
      assertThat(false, "Should fail getting queue url non-existent queue");
    } catch (AmazonServiceException e) {
      assertThat(e.getStatusCode() == 400, "Correctly fail getting queue url non-existent queue");
    }
  }

  @Test
  public void testGetQueueUrl() {
    testInfo(this.getClass().getSimpleName() + " - testGetQueueUrl");
    String queueName = "queue_name_get_queue_url";
    String queueUrl = accountSQSClient.createQueue(queueName).getQueueUrl();
    assertThat(accountSQSClient.getQueueUrl(queueName).getQueueUrl().equals(queueUrl), "queue url matches created value");
  }

}

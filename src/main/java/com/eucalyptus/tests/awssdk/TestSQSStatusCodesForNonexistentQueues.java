package com.eucalyptus.tests.awssdk;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.GetQueueUrlRequest;
import com.amazonaws.services.sqs.model.ListDeadLetterSourceQueuesRequest;
import com.amazonaws.services.sqs.model.ListQueuesResult;
import com.amazonaws.services.sqs.model.PurgeQueueRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.eucalyptus.tests.awssdk.N4j.assertThat;
import static com.eucalyptus.tests.awssdk.N4j.synchronizedCreateAccount;
import static com.eucalyptus.tests.awssdk.N4j.synchronizedCreateUser;
import static com.eucalyptus.tests.awssdk.N4j.synchronizedDeleteAccount;
import static com.eucalyptus.tests.awssdk.N4j.getCloudInfoAndSqs;
import static com.eucalyptus.tests.awssdk.N4j.getSqsClientWithNewAccount;
import static com.eucalyptus.tests.awssdk.N4j.getUserCreds;
import static com.eucalyptus.tests.awssdk.N4j.print;
import static com.eucalyptus.tests.awssdk.N4j.testInfo;

/**
 * Created by ethomas on 10/4/16.
 */
public class TestSQSStatusCodesForNonexistentQueues {


  private static String account;
  private static String otherAccount;
  private static AmazonSQS accountSQSClient;
  private static AmazonSQS accountUserSQSClient;
  private static AmazonSQS otherAccountSQSClient;
  private static AmazonSQS otherAccountUserSQSClient;

  private static String accountId;
  private static String queueUrl;
  private static String bogusQueueName;
  private static String bogusQueueUrl;
  private static Collection<NamedAmazonSQS> clients;


  @BeforeClass
  public static void init() throws Exception {
    print("### PRE SUITE SETUP - " + TestSQSStatusCodesForNonexistentQueues.class.getSimpleName());

    try {
      getCloudInfoAndSqs();
      account = "sqs-account-sc-a-" + System.currentTimeMillis();
      synchronizedCreateAccount(account);
      accountSQSClient = getSqsClientWithNewAccount(account, "admin");
      AWSCredentials accountCredentials = getUserCreds(account, "admin");
      synchronizedCreateUser(account, "user");
      accountUserSQSClient = getSqsClientWithNewAccount(account, "user");
      otherAccount = "sqs-account-sc-b-" + System.currentTimeMillis();
      synchronizedCreateAccount(otherAccount);
      otherAccountSQSClient = getSqsClientWithNewAccount(otherAccount, "admin");
      AWSCredentials otherAccountCredentials = getUserCreds(otherAccount, "admin");
      synchronizedCreateUser(otherAccount, "user");
      otherAccountUserSQSClient = getSqsClientWithNewAccount(otherAccount, "user");

      String queueName = "queue_name_status_codes_nonexistent_queue";
      queueUrl = accountSQSClient.createQueue(queueName).getQueueUrl();
      bogusQueueName = queueName + "-bogus";
      bogusQueueUrl = queueUrl + "-bogus";
      List<String> pathParts = Lists.newArrayList(Splitter.on('/').omitEmptyStrings().split(new URL(queueUrl).getPath()));
      accountId = pathParts.get(0);
      String otherQueueUrl = otherAccountSQSClient.createQueue(queueName).getQueueUrl();
      List<String> otherPathParts = Lists.newArrayList(Splitter.on('/').omitEmptyStrings().split(new URL(otherQueueUrl).getPath()));
      final String otherAccountId = otherPathParts.get(0);

      clients = Sets.newHashSet();
      clients.add(new NamedAmazonSQS("Main Account", accountSQSClient));
      clients.add(new NamedAmazonSQS("Main User", accountUserSQSClient));
      clients.add(new NamedAmazonSQS("Other Account", otherAccountSQSClient));
      clients.add(new NamedAmazonSQS("Other User", otherAccountUserSQSClient));

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
    print("### POST SUITE CLEANUP - " + TestSQSStatusCodesForNonexistentQueues.class.getSimpleName());
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

  public static class NamedAmazonSQS {
    AmazonSQS amazonSQS;
    String name;

    public AmazonSQS getAmazonSQS() {
      return amazonSQS;
    }

    public void setAmazonSQS(AmazonSQS amazonSQS) {
      this.amazonSQS = amazonSQS;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public NamedAmazonSQS(String name, AmazonSQS amazonSQS) {
      this.name = name;
      this.amazonSQS = amazonSQS;
    }
  }

    @Test
  public void testAddPermission() {
    testInfo(this.getClass().getSimpleName() + " - testAddPermission");
    Command command = new Command("AddPermission") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        sqs.addPermission(bogusQueueUrl, "label", Collections.singletonList(accountId), Collections.singletonList("SendMessage"));
      }
    };

    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testChangeMessageVisibility() {
    testInfo(this.getClass().getSimpleName() + " - testChangeMessageVisibility");
    Command command = new Command("ChangeMessageVisibility") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        sqs.changeMessageVisibility(bogusQueueUrl, "blah", 0);
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testChangeMessageVisibilityBatch() {
    testInfo(this.getClass().getSimpleName() + " - testChangeMessageVisibilityBatch");
    Command command = new Command("ChangeMessageVisibilityBatch") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        List<ChangeMessageVisibilityBatchRequestEntry> x = new ArrayList<>( );
        ChangeMessageVisibilityBatchRequestEntry e = new ChangeMessageVisibilityBatchRequestEntry();
        e.setReceiptHandle("blah");
        e.setVisibilityTimeout(0);
        e.setId("id");
        x.add(e);
        sqs.changeMessageVisibilityBatch(bogusQueueUrl, x);
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testDeleteMessage() {
    testInfo(this.getClass().getSimpleName() + " - testDeleteMessage");
    Command command = new Command("DeleteMessage") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        sqs.deleteMessage(bogusQueueUrl, "blah");
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testDeleteMessageBatch() {
    testInfo(this.getClass().getSimpleName() + " - testDeleteMessageBatch");
    Command command = new Command("DeleteMessageBatch") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        List<DeleteMessageBatchRequestEntry> x = new ArrayList<>( );
        DeleteMessageBatchRequestEntry e = new DeleteMessageBatchRequestEntry();
        e.setReceiptHandle("blah");
        e.setId("id");
        x.add(e);
        sqs.deleteMessageBatch(bogusQueueUrl, x);
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testDeleteQueue() {
    testInfo(this.getClass().getSimpleName() + " - testDeleteQueue");
    Command command = new Command("DeleteQueue") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        sqs.deleteQueue(bogusQueueUrl);
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testGetQueueAttributes() {
    testInfo(this.getClass().getSimpleName() + " - testGetQueueAttributes");
    Command command = new Command("GetQueueAttributes") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        sqs.getQueueAttributes(bogusQueueUrl, Collections.singletonList("All"));
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testGetQueueUrl() {
    testInfo(this.getClass().getSimpleName() + " - testGetQueueUrl");
    Command command = new Command("GetQueueUrl") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        GetQueueUrlRequest getQueueUrlRequest = new GetQueueUrlRequest();
        getQueueUrlRequest.setQueueOwnerAWSAccountId(accountId);
        getQueueUrlRequest.setQueueName(bogusQueueName);
        sqs.getQueueUrl(getQueueUrlRequest);
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testListDeadLetterSourceQueues() {
    testInfo(this.getClass().getSimpleName() + " - testListDeadLetterSourceQueues");
    Command command =       new Command("ListDeadLetterSourceQueues") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        ListDeadLetterSourceQueuesRequest x = new ListDeadLetterSourceQueuesRequest();
        x.setQueueUrl(bogusQueueUrl);
        sqs.listDeadLetterSourceQueues(x);
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testPurgeQueue() {
    testInfo(this.getClass().getSimpleName() + " - testPurgeQueue");
    Command command = new Command("PurgeQueue") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        PurgeQueueRequest x = new PurgeQueueRequest();
        x.setQueueUrl(bogusQueueUrl);
        sqs.purgeQueue(x);
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testReceiveMessage() {
    testInfo(this.getClass().getSimpleName() + " - testReceiveMessage");
    Command command = new Command("ReceiveMessage") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        sqs.receiveMessage(bogusQueueUrl);
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testRemovePermission() {
    testInfo(this.getClass().getSimpleName() + " - testRemovePermission");
    Command command = new Command("RemovePermission") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        sqs.removePermission(bogusQueueUrl, "label");
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testSendMessage() {
    testInfo(this.getClass().getSimpleName() + " - testSendMessage");
    Command command = new Command("SendMessage") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        sqs.sendMessage(bogusQueueUrl, "hello");
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testSendMessageBatch() {
    testInfo(this.getClass().getSimpleName() + " - testSendMessageBatch");
    Command command = new Command("SendMessageBatch") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        List<SendMessageBatchRequestEntry> x = new ArrayList<>( );
        SendMessageBatchRequestEntry e = new SendMessageBatchRequestEntry();
        e.setMessageBody("hello");
        e.setId("id");
        x.add(e);
        sqs.sendMessageBatch(bogusQueueUrl, x);
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  @Test
  public void testSetQueueAttributes() {
    testInfo(this.getClass().getSimpleName() + " - testSetQueueAttributes");
    Command command = new Command("SetQueueAttributes") {
      @Override
      public void runCommand(AmazonSQS sqs) throws AmazonServiceException {
        sqs.setQueueAttributes(bogusQueueUrl, Collections.singletonMap("VisibilityTimeout", "0"));
      }
    };
    for (NamedAmazonSQS client: clients) {
      testClientWithCommand(command, client);
    }
  }

  private static final int SUCCESS = 200;
  private static final int QUEUE_DOES_NOT_EXIST = 400;
  private static final int ACCESS_DENIED = 403;

  public abstract static class Command {
    public String getName() {
      return name;
    }

    private String name;

    protected Command(String name) {
      this.name = name;
    }

    public final int getStatusFromCommand(AmazonSQS sqs) {
      try {
        runCommand(sqs);
        return SUCCESS;
      } catch (AmazonServiceException e) {
        return e.getStatusCode();
      }
    }
    public abstract void runCommand(AmazonSQS sqs) throws AmazonServiceException;
  }

  private void testClientWithCommand(Command command, NamedAmazonSQS namedAmazonSQS) {
    int expectedCode;
    // some commands and clients should return ACCESS_DENIED
    // non "shared-queue" commands with cross-account credentials
    if (ImmutableSet.of("AddPermission", "DeleteQueue", "RemovePermission", "SetQueueAttributes").contains(command.getName()) &&
      ImmutableSet.of("Other Account", "Other User").contains(namedAmazonSQS.getName())
      ) {
      expectedCode = ACCESS_DENIED;
    } else {
      expectedCode = QUEUE_DOES_NOT_EXIST;
    }
    int actualCode = command.getStatusFromCommand(namedAmazonSQS.getAmazonSQS());
    assertThat(expectedCode == actualCode, "Calling " + command.getName() + " with client " + namedAmazonSQS.getName() + ", status code was " + actualCode + ", expected " + expectedCode);
  }



}
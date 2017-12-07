/*************************************************************************
 * Copyright 2009-2014 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 *
 * This file may incorporate work covered under the following copyright
 * and permission notice:
 *
 *   Software License Agreement (BSD License)
 *
 *   Copyright (c) 2008, Regents of the University of California
 *   All rights reserved.
 *
 *   Redistribution and use of this software in source and binary forms,
 *   with or without modification, are permitted provided that the
 *   following conditions are met:
 *
 *     Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *     Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer
 *     in the documentation and/or other materials provided with the
 *     distribution.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 *   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 *   FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 *   COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 *   INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 *   BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 *   CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 *   LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 *   ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *   POSSIBILITY OF SUCH DAMAGE. USERS OF THIS SOFTWARE ACKNOWLEDGE
 *   THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE LICENSED MATERIAL,
 *   COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS SOFTWARE,
 *   AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *   IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA,
 *   SANTA BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY,
 *   WHICH IN THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION,
 *   REPLACEMENT OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO
 *   IDENTIFIED, OR WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT
 *   NEEDED TO COMPLY WITH ANY SUCH LICENSES OR RIGHTS.
 ************************************************************************/

package com.eucalyptus.tests.awssdk;

import static com.eucalyptus.tests.awssdk.N4j.IAM_ENDPOINT;
import static com.eucalyptus.tests.awssdk.N4j.assertThat;
import static com.eucalyptus.tests.awssdk.N4j.eucaUUID;
import static com.eucalyptus.tests.awssdk.N4j.getUserKeys;
import static com.eucalyptus.tests.awssdk.N4j.getYouAreClient;
import static com.eucalyptus.tests.awssdk.N4j.initS3ClientWithNewAccount;
import static com.eucalyptus.tests.awssdk.N4j.print;
import static com.eucalyptus.tests.awssdk.N4j.testInfo;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.AfterClass;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Before;
import org.junit.Test;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.identitymanagement.model.GetUserResult;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.CanonicalGrantee;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.ListMultipartUploadsRequest;
import com.amazonaws.services.s3.model.MultipartUpload;
import com.amazonaws.services.s3.model.MultipartUploadListing;
import com.amazonaws.services.s3.model.Owner;
import com.amazonaws.services.s3.model.Permission;
import com.github.sjones4.youcan.youare.YouAre;

public class S3ListMpuTests {
  String bucketName = null;
  List<Runnable> cleanupTasks = null;
  private static AmazonS3 s3ClientA = null;
  private static String accountA = null;
  private static String accountIdA = null;
  private static Owner ownerA = null;
  private static YouAre userIamA = null;
  private static String userNameA = null;
  private static String userArnA = null;
  private static Random random = new Random();

  private static final int DEFAULT_MAX_KEYS = 1000;

  @BeforeClass
  public static void init() throws Exception {
    print("### PRE SUITE SETUP - " + S3ListMpuTests.class.getSimpleName());
    try {
      accountA = S3ListMpuTests.class.getSimpleName().toLowerCase() + 'a';
      s3ClientA = initS3ClientWithNewAccount(accountA, "admin");
      ownerA = s3ClientA.getS3AccountOwner();
      accountIdA = ownerA.getId();

      // Get the user details
      Map<String, String> keyMap = getUserKeys(accountA, "admin");
      userIamA = getYouAreClient(keyMap.get("ak"), keyMap.get("sk"), IAM_ENDPOINT);
      GetUserResult getUserResult = userIamA.getUser();
      userNameA = getUserResult.getUser().getUserName();
      userArnA = getUserResult.getUser().getArn();
    } catch (Exception e) {
      try {
        teardown();
      } catch (Exception ie) {
      }
      throw e;
    }

    // s3 = getS3Client("awsrc_euca");
  }

  public static AmazonS3 getS3Client(String credPath) throws Exception {
    print("Getting cloud information from " + credPath);

    String s3Endpoint = N4j.getAttribute(credPath, "S3_URL");

    String secretKey = N4j.getAttribute(credPath, "EC2_SECRET_KEY").replace("'", "");
    String accessKey = N4j.getAttribute(credPath, "EC2_ACCESS_KEY").replace("'", "");

    print("Initializing S3 connections");
    return N4j.getS3Client(accessKey, secretKey, s3Endpoint);
  }

  @AfterClass
  public static void teardown() throws Exception {
    print("### POST SUITE CLEANUP - " + S3ListMpuTests.class.getSimpleName());
    N4j.deleteAccount(accountA);
    s3ClientA = null;
  }

  @Before
  public void setup() throws Exception {
    bucketName = eucaUUID();
    cleanupTasks = new ArrayList<Runnable>();
    Bucket bucket = S3Utils.createBucket(s3ClientA, accountA, bucketName, S3Utils.BUCKET_CREATION_RETRIES);
    cleanupTasks.add(new Runnable() {
      @Override
      public void run() {
        print(accountA + ": Deleting bucket " + bucketName);
        s3ClientA.deleteBucket(bucketName);
      }
    });

    assertTrue("Invalid reference to bucket", bucket != null);
    assertTrue("Mismatch in bucket names. Expected bucket name to be " + bucketName + ", but got " + bucket.getName(),
        bucketName.equals(bucket.getName()));
  }

  @After
  public void cleanup() throws Exception {
    Collections.reverse(cleanupTasks);
    for (final Runnable cleanupTask : cleanupTasks) {
      try {
        cleanupTask.run();
      } catch (Exception e) {
        print("Unable to run clean up task: " + e);
      }
    }
  }

  @Test
  public void oneKeyMultipleUploads() throws Exception {
    testInfo(this.getClass().getSimpleName() + " - oneKeyMultipleUploads");
    try {
      String key = UUID.randomUUID().toString().replaceAll("-", "");
      int numUploads = 3 + random.nextInt(3);// 3-5 uploads

      print("Number of uploads for the same key: " + numUploads);

      // Initiate a bunch of mpus
      List<String> uploadIdList = initiateMpusForKey(s3ClientA, accountA, key, bucketName, numUploads);

      // Verify the entire mpu listing
      MultipartUploadListing listing = listMpu(s3ClientA, accountA, bucketName, null, null, null, null, null, false);
      assertTrue("Expected " + numUploads + " mpu listings, but got " + listing.getMultipartUploads().size(), numUploads == listing
          .getMultipartUploads().size());
      for (int i = 0; i < numUploads; i++) {
        MultipartUpload mpu = listing.getMultipartUploads().get(i);
        assertTrue("Expected key to be " + key + ", but got " + mpu.getKey(), mpu.getKey().equals(key));
        assertTrue("Expected upload ID to be " + uploadIdList.get(i) + ", but got " + mpu.getUploadId(), mpu.getUploadId()
            .equals(uploadIdList.get(i)));
        verifyCommonElements(mpu);
      }

    } catch (AmazonServiceException ase) {
      printException(ase);
      assertThat(false, "Failed to run oneKeyMultipleUploads");
    }
  }

  @Test
  public void keyMarker() throws Exception {
    testInfo(this.getClass().getSimpleName() + " - keyMarker");

    try {
      int numKeys = 3 + random.nextInt(3); // 3-5 keys
      int numUploads = 3 + random.nextInt(3); // 3-5 uploads

      print("Number of keys: " + numKeys);
      print("Number of uploads per key: " + numUploads);

      // Generate some mpus
      TreeMap<String, List<String>> keyUploadIdMap = initiateMpusForMultipleKeys(s3ClientA, accountA, numKeys, numUploads, new String());

      // Starting with every key in the ascending order, list the mpus using that key as the key marker and verify that the results.
      for (String keyMarker : keyUploadIdMap.keySet()) {

        // Compute what the sorted mpus should look like
        NavigableMap<String, List<String>> tailMap = keyUploadIdMap.tailMap(keyMarker, false);

        // List mpus using the key marker and verify
        MultipartUploadListing listing = listMpu(s3ClientA, accountA, bucketName, keyMarker, null, null, null, null, false);
        assertTrue("Expected " + (tailMap.size() * numUploads) + " mpu listings, but got " + listing.getMultipartUploads().size(),
            (tailMap.size() * numUploads) == listing.getMultipartUploads().size());

        Iterator<MultipartUpload> mpuIterator = listing.getMultipartUploads().iterator();

        for (Entry<String, List<String>> tailMapEntry : tailMap.entrySet()) {
          for (String uploadId : tailMapEntry.getValue()) {
            MultipartUpload mpu = mpuIterator.next();
            assertTrue("Expected key to be " + tailMapEntry.getKey() + ", but got " + mpu.getKey(), mpu.getKey().equals(tailMapEntry.getKey()));
            assertTrue("Expected upload ID to be " + uploadId + ", but got " + mpu.getUploadId(), mpu.getUploadId().equals(uploadId));
            verifyCommonElements(mpu);
          }
        }

        assertTrue("Expected mpu iterator to be empty", !mpuIterator.hasNext());
      }

    } catch (AmazonServiceException ase) {
      printException(ase);
      assertThat(false, "Failed to run keyMarker");
    }
  }

  @Test
  public void keyMarkerUploadIdMarker() throws Exception {
    testInfo(this.getClass().getSimpleName() + " - keyMarkerUploadIdMarker");

    try {
      int numKeys = 3 + random.nextInt(3); // 3-5 keys
      int numUploads = 3 + random.nextInt(3); // 3-5 uploads

      print("Number of keys: " + numKeys);
      print("Number of uploads per key: " + numUploads);

      // Generate some mpus
      TreeMap<String, List<String>> keyUploadIdMap = initiateMpusForMultipleKeys(s3ClientA, accountA, numKeys, numUploads, new String());

      // Starting with every key and upload ID in the ascending order, list the mpus using the pair and verify that the results.
      for (Map.Entry<String, List<String>> mapEntry : keyUploadIdMap.entrySet()) {

        // Compute what the sorted mpus should look like
        NavigableMap<String, List<String>> tailMap = keyUploadIdMap.tailMap(mapEntry.getKey(), false);

        for (int i = 0; i < numUploads; i++) {
          // Compute what the sorted uploadIds should look like this key
          List<String> tailList = mapEntry.getValue().subList(i + 1, numUploads);

          // List mpus using the key marker and upload ID marker and verify
          MultipartUploadListing listing =
              listMpu(s3ClientA, accountA, bucketName, mapEntry.getKey(), mapEntry.getValue().get(i), null, null, null, false);
          assertTrue("Expected " + ((tailMap.size() * numUploads) + (numUploads - i - 1)) + " mpu listings, but got "
              + listing.getMultipartUploads().size(), ((tailMap.size() * numUploads) + (numUploads - i - 1)) == listing.getMultipartUploads().size());

          Iterator<MultipartUpload> mpuIterator = listing.getMultipartUploads().iterator();

          for (String uploadId : tailList) {
            MultipartUpload mpu = mpuIterator.next();
            assertTrue("Expected key to be " + mapEntry.getKey() + ", but got " + mpu.getKey(), mpu.getKey().equals(mapEntry.getKey()));
            assertTrue("Expected upload ID to be " + uploadId + ", but got " + mpu.getUploadId(), mpu.getUploadId().equals(uploadId));
            verifyCommonElements(mpu);
          }

          for (Entry<String, List<String>> tailMapEntry : tailMap.entrySet()) {
            for (String uploadId : tailMapEntry.getValue()) {
              MultipartUpload mpu = mpuIterator.next();
              assertTrue("Expected key to be " + tailMapEntry.getKey() + ", but got " + mpu.getKey(), mpu.getKey().equals(tailMapEntry.getKey()));
              assertTrue("Expected upload ID to be " + uploadId + ", but got " + mpu.getUploadId(), mpu.getUploadId().equals(uploadId));
              verifyCommonElements(mpu);
            }
          }

          assertTrue("Expected mpu iterator to be empty", !mpuIterator.hasNext());
        }
      }
    } catch (AmazonServiceException ase) {
      printException(ase);
      assertThat(false, "Failed to run keyMarkerUploadIdMarker");
    }
  }

  @Test
  public void prefix() throws Exception {
    testInfo(this.getClass().getSimpleName() + " - prefix");
    try {
      int numKeys = 3 + random.nextInt(3); // 3-5 keys
      int numUploads = 3 + random.nextInt(3); // 3-5 uploads

      print("Number of keys: " + numKeys);
      print("Number of uploads per key: " + numUploads);

      // Generate some mpus
      TreeMap<String, List<String>> keyUploadIdMap = initiateMpusForMultipleKeys(s3ClientA, accountA, numKeys, numUploads, new String());

      // Using each key as the prefix in the listing, verify the listing for that specific key
      for (Entry<String, List<String>> mapEntry : keyUploadIdMap.entrySet()) {
        MultipartUploadListing listing = listMpu(s3ClientA, accountA, bucketName, null, null, mapEntry.getKey(), null, null, false);
        assertTrue("Expected " + numUploads + " mpu listings, but got " + listing.getMultipartUploads().size(), numUploads == listing
            .getMultipartUploads().size());
        for (int i = 0; i < numUploads; i++) {
          MultipartUpload mpu = listing.getMultipartUploads().get(i);
          assertTrue("Expected key to be " + mapEntry.getKey() + ", but got " + mpu.getKey(), mpu.getKey().equals(mapEntry.getKey()));
          assertTrue("Expected upload ID to be " + mapEntry.getValue().get(i) + ", but got " + mpu.getUploadId(),
              mpu.getUploadId().equals(mapEntry.getValue().get(i)));
          verifyCommonElements(mpu);
        }
      }

      // Verify the entire mpu listing
      MultipartUploadListing listing = listMpu(s3ClientA, accountA, bucketName, null, null, null, null, null, false);
      assertTrue("Expected " + (numKeys * numUploads) + " mpu listings, but got " + listing.getMultipartUploads().size(),
          (numKeys * numUploads) == listing.getMultipartUploads().size());

      Iterator<MultipartUpload> mpuIterator = listing.getMultipartUploads().iterator();

      for (Entry<String, List<String>> mapEntry : keyUploadIdMap.entrySet()) {
        for (String uploadId : mapEntry.getValue()) {
          MultipartUpload mpu = mpuIterator.next();
          assertTrue("Expected key to be " + mapEntry.getKey() + ", but got " + mpu.getKey(), mpu.getKey().equals(mapEntry.getKey()));
          assertTrue("Expected upload ID to be " + uploadId + ", but got " + mpu.getUploadId(), mpu.getUploadId().equals(uploadId));
          verifyCommonElements(mpu);
        }
      }

      assertTrue("Expected mpu iterator to be empty", !mpuIterator.hasNext());
    } catch (AmazonServiceException ase) {
      printException(ase);
      assertThat(false, "Failed to run prefix");
    }
  }

  @Test
  public void delimiterAndPrefix() throws Exception {
    testInfo(this.getClass().getSimpleName() + " - delimiterAndPrefix");
    try {
      int numPrefixes = 3 + random.nextInt(3); // 3-5 prefixes
      int numKeys = 3 + random.nextInt(3); // 3-5 keys
      int numUploads = 3 + random.nextInt(3); // 3-5 uploads
      String delimiter = "/";
      TreeMap<String, TreeMap<String, List<String>>> prefixKeyUploadIdMap = new TreeMap<String, TreeMap<String, List<String>>>();

      print("Number of prefixes: " + numPrefixes);
      print("Number of keys per prefix: " + numKeys);
      print("Number of uploads per key: " + numUploads);

      // Generate some keys and uploads for each prefix
      for (int i = 0; i < numPrefixes; i++) {
        String prefix = UUID.randomUUID().toString().replaceAll("-", "") + delimiter;

        // Generate some mpus for keys starting with prefix
        TreeMap<String, List<String>> keyUploadIdMap = initiateMpusForMultipleKeys(s3ClientA, accountA, numKeys, numUploads - 1, prefix);

        // Generate some mpus for a key that is just the prefix
        keyUploadIdMap.put(prefix, initiateMpusForKey(s3ClientA, accountA, prefix, bucketName, numUploads));

        // Put the prefix and key-uploadId into the map
        prefixKeyUploadIdMap.put(prefix, keyUploadIdMap);
      }

      // Using the delimiter verify the mpu listing for common prefixes
      MultipartUploadListing listing = listMpu(s3ClientA, accountA, bucketName, null, null, null, delimiter, null, false);
      assertTrue("Expected no multipart uploads but got some", listing.getMultipartUploads() == null || listing.getMultipartUploads().isEmpty());
      assertTrue("Expected " + numPrefixes + " common prefixes but got " + listing.getCommonPrefixes().size(),
          listing.getCommonPrefixes().size() == numPrefixes);

      Iterator<String> commonPrefixIterator = listing.getCommonPrefixes().iterator();

      for (String prefix : prefixKeyUploadIdMap.keySet()) {
        String commonPrefix = commonPrefixIterator.next();
        assertTrue("Expected common prefix to be " + prefix + ", but got " + commonPrefix, Objects.equals(prefix, commonPrefix));
      }

      assertTrue("Expected common prefixes iterator to be empty", !commonPrefixIterator.hasNext());

      // Using both prefix and delimiter, verify that mpu listing contains only one common prefix
      for (String prefix : prefixKeyUploadIdMap.keySet()) {
        // Remove the delimiter from the prefix before listing
        listing = listMpu(s3ClientA, accountA, bucketName, null, null, new String(prefix).replaceAll(delimiter, ""), delimiter, null, false);
        assertTrue("Expected 1 common prefix but got " + listing.getCommonPrefixes().size(), listing.getCommonPrefixes().size() == 1);
        assertTrue("Expected common prefix to be " + prefix + ", but got " + listing.getCommonPrefixes().get(0),
            Objects.equals(listing.getCommonPrefixes().get(0), prefix));
        assertTrue("Expected no multipart uploads but got some", listing.getMultipartUploads() == null || listing.getMultipartUploads().isEmpty());
      }
    } catch (AmazonServiceException ase) {
      printException(ase);
      assertThat(false, "Failed to run delimiterAndPrefix");
    }
  }

  @Test
  public void maxKeys() throws Exception {
    testInfo(this.getClass().getSimpleName() + " - maxKeys");
    try {
      int numKeys = 3 + random.nextInt(3); // 3-5 keys
      int numUploads = 3 + random.nextInt(3); // 3-5 uploads
      int maxUploads = numUploads - 1;
      int totalUploads = numKeys * numUploads;
      int counter = (totalUploads % maxUploads == 0) ? (totalUploads / maxUploads) : ((totalUploads / maxUploads) + 1);

      print("Number of keys: " + numKeys);
      print("Number of uploads per key: " + numUploads);
      print("Number of mpus per listing: " + maxUploads);

      // Generate some mpus
      TreeMap<String, List<String>> keyUploadIdMap = initiateMpusForMultipleKeys(s3ClientA, accountA, numKeys, numUploads, new String());

      Iterator<String> keyIterator = keyUploadIdMap.keySet().iterator();
      String key = keyIterator.next();
      Iterator<String> uploadIdIterator = keyUploadIdMap.get(key).iterator();
      String uploadId = null;

      String nextKeyMarker = null;
      String nextUploadIdMarker = null;
      MultipartUploadListing listing = null;

      for (int i = 1; i <= counter; i++) {
        if (i != counter) {
          listing = listMpu(s3ClientA, accountA, bucketName, nextKeyMarker, nextUploadIdMarker, null, null, maxUploads, true);
          assertTrue("Expected " + maxUploads + " mpu listings, but got " + listing.getMultipartUploads().size(), maxUploads == listing
              .getMultipartUploads().size());
        } else {
          listing = listMpu(s3ClientA, accountA, bucketName, nextKeyMarker, nextUploadIdMarker, null, null, maxUploads, false);
          assertTrue("Expected " + totalUploads + " mpu listings, but got " + listing.getMultipartUploads().size(), totalUploads == listing
              .getMultipartUploads().size());
        }

        for (MultipartUpload mpu : listing.getMultipartUploads()) {
          if (!uploadIdIterator.hasNext()) {
            key = keyIterator.next();
            uploadIdIterator = keyUploadIdMap.get(key).iterator();
          }
          uploadId = uploadIdIterator.next();
          assertTrue("Expected key to be " + key + ", but got " + mpu.getKey(), mpu.getKey().equals(key));
          assertTrue("Expected upload ID to be " + uploadId + ", but got " + mpu.getUploadId(), mpu.getUploadId().equals(uploadId));
          verifyCommonElements(mpu);
          totalUploads--;
        }

        nextKeyMarker = key;
        nextUploadIdMarker = uploadId;
      }
    } catch (AmazonServiceException ase) {
      printException(ase);
      assertThat(false, "Failed to run maxKeys");
    }
  }

  /**
   * Test to verify ACL privileges required to list multipart uploads in a bucket. ACL privileges to list uploads is the same as listing objects or
   * versions - account listing multipart uploads must have READ or FULL_CONTROL on bucket
   */
  @Test
  public void listUploadsMultipleAccounts() throws Exception {
    testInfo(this.getClass().getSimpleName() + " - listUploadsMultipleAccounts");
    String accountB = null;
    String accountC = null;
    AmazonS3 s3ClientB = null;
    AmazonS3 s3ClientC = null;
    Owner ownerB = null;
    Owner ownerC = null;

    try {
      accountB = this.getClass().getSimpleName().toLowerCase() + 'b';
      s3ClientB = initS3ClientWithNewAccount(accountB, "admin");
      ownerB = s3ClientB.getS3AccountOwner();

      accountC = this.getClass().getSimpleName().toLowerCase() + 'c';
      s3ClientC = initS3ClientWithNewAccount(accountC, "admin");
      ownerC = s3ClientC.getS3AccountOwner();

      /* Configure bucket ACL to allow FULL_CONTROL to account B and READ to account C */
      AccessControlList acl = new AccessControlList();
      acl.setOwner(ownerA);
      acl.grantPermission(new CanonicalGrantee(ownerB.getId()), Permission.FullControl);
      acl.grantPermission(new CanonicalGrantee(ownerC.getId()), Permission.Read);
      print(accountA + ": Setting acl on bucket " + bucketName + " to " + acl);
      s3ClientA.setBucketAcl(bucketName, acl);

      String key = UUID.randomUUID().toString().replaceAll("-", "");
      int numUploads = 3 + random.nextInt(3);// 3-5 uploads

      print("Number of uploads for the same key: " + numUploads);

      /* Initiate a bunch of mpus as account B */
      List<String> uploadIdList = initiateMpusForKey(s3ClientB, accountB, key, bucketName, numUploads);

      /* Verify bucket owning account A can list the uploads */
      MultipartUploadListing listing = listMpu(s3ClientA, accountA, bucketName, null, null, null, null, null, false);
      assertTrue("Expected " + numUploads + " mpu listings, but got " + listing.getMultipartUploads().size(), numUploads == listing
          .getMultipartUploads().size());
      for (int i = 0; i < numUploads; i++) {
        MultipartUpload mpu = listing.getMultipartUploads().get(i);
        assertTrue("Expected key to be " + key + ", but got " + mpu.getKey(), mpu.getKey().equals(key));
        assertTrue("Expected upload ID to be " + uploadIdList.get(i) + ", but got " + mpu.getUploadId(), mpu.getUploadId()
            .equals(uploadIdList.get(i)));
      }

      /* Verify mpu initiating account B can list the uploads */
      listing = listMpu(s3ClientB, accountB, bucketName, null, null, null, null, null, false);
      assertTrue("Expected " + numUploads + " mpu listings, but got " + listing.getMultipartUploads().size(), numUploads == listing
          .getMultipartUploads().size());
      for (int i = 0; i < numUploads; i++) {
        MultipartUpload mpu = listing.getMultipartUploads().get(i);
        assertTrue("Expected key to be " + key + ", but got " + mpu.getKey(), mpu.getKey().equals(key));
        assertTrue("Expected upload ID to be " + uploadIdList.get(i) + ", but got " + mpu.getUploadId(), mpu.getUploadId()
            .equals(uploadIdList.get(i)));
      }

      /* Verify account C with READ on bucket can list the uploads */
      listing = listMpu(s3ClientC, accountC, bucketName, null, null, null, null, null, false);
      assertTrue("Expected " + numUploads + " mpu listings, but got " + listing.getMultipartUploads().size(), numUploads == listing
          .getMultipartUploads().size());
      for (int i = 0; i < numUploads; i++) {
        MultipartUpload mpu = listing.getMultipartUploads().get(i);
        assertTrue("Expected key to be " + key + ", but got " + mpu.getKey(), mpu.getKey().equals(key));
        assertTrue("Expected upload ID to be " + uploadIdList.get(i) + ", but got " + mpu.getUploadId(), mpu.getUploadId()
            .equals(uploadIdList.get(i)));
      }

      /* Configure bucket ACL to allow FULL_CONTROL to account B */
      acl.revokeAllPermissions(new CanonicalGrantee(ownerC.getId()));
      print(accountA + ": Setting acl on bucket " + bucketName + " to " + acl);
      s3ClientA.setBucketAcl(bucketName, acl);

      /* Verify account C cannot list uploads */
      boolean caughtError = false;
      try {
        listing = listMpu(s3ClientC, accountC, bucketName, null, null, null, null, null, false);
      } catch (AmazonServiceException ase) {
        caughtError = true;
        assertTrue("Expected HTTP status code to be 403 but got " + ase.getStatusCode(), ase.getStatusCode() == 403);
        assertTrue("Expected AccessDenied error code but got " + ase.getErrorCode(), ase.getErrorCode().equals("AccessDenied"));
      } finally {
        assertTrue("Expected 403 AccessDenied response", caughtError);
      }
    } catch (AmazonServiceException ase) {
      printException(ase);
      assertThat(false, "Failed to run listUploadsMultipleAccounts");
    } finally {
      N4j.deleteAccount(accountB);
      N4j.deleteAccount(accountC);
      s3ClientB = null;
      s3ClientC = null;
    }
  }

  private TreeMap<String, List<String>> initiateMpusForMultipleKeys(AmazonS3 s3, String accountName, int numKeys, int numUploads, String prefix) {
    TreeMap<String, List<String>> keyUploadIdMap = new TreeMap<String, List<String>>();

    // Cycle through a bunch of different keys
    for (int i = 0; i < numKeys; i++) {
      String key = prefix + UUID.randomUUID().toString().replaceAll("-", "");

      // Add the key and upload ID list to the treemap
      keyUploadIdMap.put(key, initiateMpusForKey(s3, accountA, key, bucketName, numUploads));
    }
    assertTrue("Expected " + numKeys + " keys, but got " + keyUploadIdMap.size(), numKeys == keyUploadIdMap.size());

    return keyUploadIdMap;
  }

  private List<String> initiateMpusForKey(AmazonS3 s3, String accountName, String key, String bucketName, int numUploads) {
    List<String> uploadIdList = new ArrayList<String>();

    // Initiate a bunch of mpus
    for (int j = 0; j < numUploads; j++) {
      print(accountName + ": Initiating multipart upload for object " + key + " in bucket " + bucketName);
      uploadIdList.add(s3.initiateMultipartUpload(new InitiateMultipartUploadRequest(bucketName, key)).getUploadId());
    }
    // Sort the upload IDs lexicographically
    Collections.sort(uploadIdList);

    return uploadIdList;
  }

  private void verifyCommonElements(MultipartUpload mpu) {
    assertTrue("Expected initiator ID to be " + userArnA + ", but got " + mpu.getInitiator().getId(),
        Objects.equals(mpu.getInitiator().getId(), userArnA));
    assertTrue("Expected initiator name to be " + userNameA + ", but got " + mpu.getInitiator().getDisplayName(),
        Objects.equals(mpu.getInitiator().getDisplayName(), userNameA));
    assertTrue("Expected account ID to be " + accountIdA + ", but got " + mpu.getOwner().getId(),
        Objects.equals(mpu.getOwner().getId(), accountIdA));
    assertTrue("Expected account ID to be " + accountA + ", but got " + mpu.getOwner().getDisplayName(),
        Objects.equals(mpu.getOwner().getDisplayName(), accountA));
    assertTrue("Expected storage class to be STANDARD, but got " + mpu.getStorageClass(),
        Objects.equals(mpu.getStorageClass(), "STANDARD"));
  }

  private MultipartUploadListing listMpu(AmazonS3 s3, String accountName, String bucketName, String keyMarker, String uploadIdMarker, String prefix,
      String delimiter, Integer maxUploads, boolean isTruncated) {

    ListMultipartUploadsRequest request = new ListMultipartUploadsRequest(bucketName);
    StringBuilder sb = new StringBuilder(accountName + ": List multipart uploads using bucket=" + bucketName);

    if (keyMarker != null) {
      request.setKeyMarker(keyMarker);
      sb.append(", key marker=").append(keyMarker);
    }
    if (uploadIdMarker != null) {
      request.setUploadIdMarker(uploadIdMarker);
      sb.append(", upload ID marker=").append(uploadIdMarker);
    }
    if (prefix != null) {
      request.setPrefix(prefix);
      sb.append(", prefix=").append(prefix);
    }
    if (delimiter != null) {
      request.setDelimiter(delimiter);
      sb.append(", delimiter=").append(delimiter);
    }
    if (maxUploads != null) {
      request.setMaxUploads(maxUploads);
      sb.append(", max uploads=").append(maxUploads);
    }

    print(sb.toString());
    MultipartUploadListing mpuListing = s3.listMultipartUploads(request);

    assertTrue("Invalid multipart upload list", mpuListing != null);
    assertTrue("Expected bucket name to be " + bucketName + ", but got " + mpuListing.getBucketName(), mpuListing.getBucketName().equals(bucketName));
    assertTrue("Expected key-marker to be " + keyMarker + ", but got " + mpuListing.getKeyMarker(),
        Objects.equals(mpuListing.getKeyMarker(), keyMarker));
    assertTrue("Expected upload-id-marker to be " + uploadIdMarker + ", but got " + mpuListing.getUploadIdMarker(),
        Objects.equals(mpuListing.getUploadIdMarker(), uploadIdMarker));
    assertTrue("Expected prefix to be " + prefix + ", but got " + mpuListing.getPrefix(), Objects.equals(mpuListing.getPrefix(), prefix));
    assertTrue("Expected delimiter to be " + delimiter + ", but got " + mpuListing.getDelimiter(),
        Objects.equals(mpuListing.getDelimiter(), delimiter));
    assertTrue("Expected max-keys to be " + (maxUploads != null ? maxUploads : DEFAULT_MAX_KEYS) + ", but got " + mpuListing.getMaxUploads(),
        mpuListing.getMaxUploads() == (maxUploads != null ? maxUploads : DEFAULT_MAX_KEYS));
    assertTrue("Expected is truncated to be " + isTruncated + ", but got " + mpuListing.isTruncated(), mpuListing.isTruncated() == isTruncated);

    if (mpuListing.getMultipartUploads() != null && !mpuListing.getMultipartUploads().isEmpty()) {
      MultipartUpload lastMpu = mpuListing.getMultipartUploads().get(mpuListing.getMultipartUploads().size() - 1);
      assertTrue("Expected next-key-marker to be " + lastMpu.getKey() + ", but got " + mpuListing.getNextKeyMarker(),
          Objects.equals(lastMpu.getKey(), mpuListing.getNextKeyMarker()));
      assertTrue("Expected next-upload-id-marker to be " + lastMpu.getUploadId() + ", but got " + mpuListing.getNextUploadIdMarker(),
          Objects.equals(lastMpu.getUploadId(), mpuListing.getNextUploadIdMarker()));
    } else {
      assertTrue("Expected next-key-marker to be null, but got " + mpuListing.getNextKeyMarker(), mpuListing.getNextKeyMarker() == null);
      assertTrue("Expected next-upload-id-marker to be null, but got " + mpuListing.getNextUploadIdMarker(),
          mpuListing.getNextUploadIdMarker() == null);
    }

    return mpuListing;
  }

  private void printException(AmazonServiceException ase) {
    print("Caught Exception: " + ase.getMessage());
    ase.printStackTrace();
  }
}

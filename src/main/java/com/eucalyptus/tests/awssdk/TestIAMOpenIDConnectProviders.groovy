package com.eucalyptus.tests.awssdk

import com.amazonaws.AmazonServiceException
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement
import com.amazonaws.services.identitymanagement.model.AddClientIDToOpenIDConnectProviderRequest
import com.amazonaws.services.identitymanagement.model.CreateAccessKeyRequest
import com.amazonaws.services.identitymanagement.model.CreateOpenIDConnectProviderRequest
import com.amazonaws.services.identitymanagement.model.CreateUserRequest
import com.amazonaws.services.identitymanagement.model.DeleteAccessKeyRequest
import com.amazonaws.services.identitymanagement.model.DeleteOpenIDConnectProviderRequest
import com.amazonaws.services.identitymanagement.model.DeleteUserPolicyRequest
import com.amazonaws.services.identitymanagement.model.DeleteUserRequest
import com.amazonaws.services.identitymanagement.model.GetOpenIDConnectProviderRequest
import com.amazonaws.services.identitymanagement.model.NoSuchEntityException
import com.amazonaws.services.identitymanagement.model.PutUserPolicyRequest
import com.amazonaws.services.identitymanagement.model.RemoveClientIDFromOpenIDConnectProviderRequest
import com.amazonaws.services.identitymanagement.model.UpdateOpenIDConnectProviderThumbprintRequest

import org.junit.AfterClass
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test

/**
 * Tests functionality for IAM OpenID Connect providers.
 *
 * Related issues:
 *   https://eucalyptus.atlassian.net/browse/EUCA-12565
 *   https://eucalyptus.atlassian.net/browse/EUCA-12567
 *   https://eucalyptus.atlassian.net/browse/EUCA-12568
 *   https://eucalyptus.atlassian.net/browse/EUCA-12574
 *   https://eucalyptus.atlassian.net/browse/EUCA-12739
 *
 * Related AWS doc:
 *   http://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_providers_create_oidc.html
 *
 */
class TestIAMOpenIDConnectProviders {

  private static AWSCredentialsProvider credentials
  private static String testAcct

  @BeforeClass
  static void init( ) {
    N4j.getCloudInfo( )
    this.testAcct= "${N4j.NAME_PREFIX}oidc-test-acct"
    N4j.createAccount(testAcct)
    this.credentials = new AWSStaticCredentialsProvider( N4j.getUserCreds(testAcct, 'admin') )
  }

  @AfterClass
  static void cleanup( ) throws Exception {
    N4j.deleteAccount(testAcct)
  }

  private AmazonIdentityManagement getIamClient( AWSCredentialsProvider credentialsProvider = credentials ) {
    AWSCredentials creds = credentialsProvider.getCredentials( )
    N4j.getYouAreClient( creds.AWSAccessKeyId, creds.AWSSecretKey, N4j.IAM_ENDPOINT )
  }

  @Test
  void testOpenIDConnectProviderManagement( ) throws Exception {
    N4j.testInfo( TestIAMOpenIDConnectProviders.simpleName )
    final String namePrefix = UUID.randomUUID().toString().substring(0,8) + "-"
    N4j.print "Using resource prefix for test: ${namePrefix}"

    final List<Runnable> cleanupTasks = [] as List<Runnable>
    try {
      String openIdConnectProviderArn = getIamClient( ).with {
        Map<String,Object> validParameters = [
            url: 'https://auth.test.com',
            thumbprintList: [
              '0' * 40
            ]
        ]
        List<Map<String,Object>> invalidParametersList = [
            [
                url: '',
            ],
            [
                url: 'https://auth.test.com/' + ( 'a' * 250 )
            ],
            [
                thumbprintList: [ ]
            ],
            [
                thumbprintList: [ '0000' ]
            ],
            [
                thumbprintList: [ 'A' * 256 ]
            ],
            [
                clientIDList: [ 'a' * 256 ]
            ],
        ]

        invalidParametersList.each{ invalidParameters ->
          try {
            Map<String,Object> parameters = [:]
            parameters << validParameters
            parameters << invalidParameters
            N4j.print "Testing provider creation with invalid parameters: ${parameters}"
            createOpenIDConnectProvider( new CreateOpenIDConnectProviderRequest( parameters ) ).with {
              N4j.print "Deleting provider: ${it.openIDConnectProviderArn}"
              deleteOpenIDConnectProvider( new DeleteOpenIDConnectProviderRequest(
                  openIDConnectProviderArn: it.openIDConnectProviderArn
              ) )
              Assert.assertTrue('Expected creation to fail', false)
            }
          } catch( AmazonServiceException e ) {
            N4j.print e.toString( )
            Assert.assertTrue("Expected status code 400, but was: ${e.statusCode}", e.statusCode == 400)
            Assert.assertTrue("Expected error code ValidationError, but was: ${e.errorCode}", e.errorCode == 'ValidationError')
          }
        }

        List<Map<String,Object>> invalidLimitList = [
            [
                clientIDList: [ 'a' ] * 101
            ],
        ]

        invalidLimitList.each{ invalidParameters ->
          try {
            Map<String,Object> parameters = [:]
            parameters << validParameters
            parameters << invalidParameters
            N4j.print "Testing provider creation with invalid parameters: ${parameters}"
            createOpenIDConnectProvider( new CreateOpenIDConnectProviderRequest( parameters ) ).with {
              N4j.print "Deleting provider: ${it.openIDConnectProviderArn}"
              deleteOpenIDConnectProvider( new DeleteOpenIDConnectProviderRequest(
                  openIDConnectProviderArn: it.openIDConnectProviderArn
              ) )
              Assert.assertTrue('Expected creation to fail', false)
            }
          } catch( AmazonServiceException e ) {
            N4j.print e.toString( )
            Assert.assertTrue("Expected status code 409, but was: ${e.statusCode}", e.statusCode == 409)
            Assert.assertTrue("Expected error code LimitExceeded, but was: ${e.errorCode}", e.errorCode == 'LimitExceeded')
          }
        }

        N4j.print "Getting account summary to check existing provider count"
        final Integer providers = getAccountSummary( ).with {
          summaryMap.get( "Providers" )
        }
        N4j.assertThat( providers != null, "Expected provider count in account summary" )
        N4j.print "Found provider count ${providers}"

        N4j.print "Creating provider : ${validParameters}"
        final String providerArn = createOpenIDConnectProvider( new CreateOpenIDConnectProviderRequest( validParameters ) )?.with {
          it.openIDConnectProviderArn
        }
        N4j.print "Created provider with arn : ${providerArn}"
        Assert.assertTrue("Expected provider arn", providerArn != null)
        Assert.assertTrue("Expected provider arn to match iam service", providerArn.startsWith('arn:aws:iam:'))
        Assert.assertTrue("Expected provider arn to contain resource type", providerArn.contains(':oidc-provider/'))
        Assert.assertTrue("Expected provider arn to end with host/path", providerArn.endsWith(validParameters.url.substring(7)))

        N4j.print "Getting account summary to check existing provider count"
        getAccountSummary( ).with {
          Integer providersInc = summaryMap.get( "Providers" )
          N4j.assertThat( providersInc != null, "Expected provider count in account summary" )
          N4j.assertThat( (providers+1) == providersInc, "Expected provider count ${providers+1}, but was: ${providersInc}" )
          N4j.print "Found provider count ${providersInc}"
        }

        N4j.print "Deleting provider : ${providerArn}"
        deleteOpenIDConnectProvider( new DeleteOpenIDConnectProviderRequest(
            openIDConnectProviderArn: providerArn
        ) )

        List<String> invalidArns = [
            '',
            'arn-aws-iam::012345678901:oidc-provider/blah.test.com', // invalid syntax
            'arn:aws:iam:::op/a',
            providerArn + '/' + ( 'a' * 2048 )
        ]
        invalidArns.each { String invalidArn ->
          try {
            N4j.print "Deleting provider using invalid arn: ${invalidArn}"
            deleteOpenIDConnectProvider( new DeleteOpenIDConnectProviderRequest(
                openIDConnectProviderArn: invalidArn
            ) )
            Assert.assertTrue('Expected deletion to fail', false)
          } catch( AmazonServiceException e ) {
            N4j.print e.toString( )
            Assert.assertTrue("Expected status code 400, but was: ${e.statusCode}", e.statusCode == 400)
            Assert.assertTrue("Expected error code ValidationError, but was: ${e.errorCode}", e.errorCode == 'ValidationError')
          }
        }

        N4j.print "Listing providers to ensure deleted"
        listOpenIDConnectProviders( ).with {
          int found = openIDConnectProviderList?.findAll{ it.arn == providerArn }?.size( ) ?: 0
          Assert.assertTrue("Expected provider was deleted: ${providerArn}", found == 0)
        }

        Map<String,Object> pathParameters = [:]
        pathParameters << validParameters
        pathParameters << [ url: 'https://auth.test.com' ]
        N4j.print "Creating provider with path: ${pathParameters}"
        final String pathProviderArn = createOpenIDConnectProvider( new CreateOpenIDConnectProviderRequest( pathParameters ) )?.with {
          it.openIDConnectProviderArn
        }
        Assert.assertTrue("Expected provider arn", pathProviderArn != null)
        Assert.assertTrue("Expected provider arn to end with host/path", pathProviderArn.endsWith(validParameters.url.substring(7)))
        cleanupTasks.add{
          N4j.print "Deleting provider : ${providerArn}"
          deleteOpenIDConnectProvider( new DeleteOpenIDConnectProviderRequest(
              openIDConnectProviderArn: pathProviderArn
          ) )
        }

        N4j.print "Listing providers to ensure present"
        listOpenIDConnectProviders( ).with {
          int found = openIDConnectProviderList?.findAll{ it.arn == pathProviderArn }?.size( ) ?: 0
          Assert.assertTrue("Expected provider listed once: ${pathProviderArn}", found == 1)
        }

        N4j.print "Getting provider: ${pathProviderArn}"
        getOpenIDConnectProvider( new GetOpenIDConnectProviderRequest(
            openIDConnectProviderArn: pathProviderArn
        ) ).with {
          Assert.assertTrue("Expected url", url != null)
          Assert.assertTrue("Expected create date", createDate != null)
          Assert.assertTrue("Expected thumbprint", thumbprintList != null && thumbprintList.size() == 1)
        }

        invalidArns.each { String invalidArn ->
          try {
            N4j.print "Getting provider using invalid arn: ${invalidArn}"
            getOpenIDConnectProvider( new GetOpenIDConnectProviderRequest(
                openIDConnectProviderArn: invalidArn
            ) )
            Assert.assertTrue('Expected get to fail', false)
          } catch( AmazonServiceException e ) {
            N4j.print e.toString( )
            Assert.assertTrue("Expected status code 400, but was: ${e.statusCode}", e.statusCode == 400)
            Assert.assertTrue("Expected error code ValidationError, but was: ${e.errorCode}", e.errorCode == 'ValidationError')
          }
        }

        N4j.print( "Updating thumbprint list for provider: ${pathProviderArn}" )
        updateOpenIDConnectProviderThumbprint( new UpdateOpenIDConnectProviderThumbprintRequest(
            openIDConnectProviderArn: pathProviderArn,
            thumbprintList: [
                '1' * 40
            ]
        ) )

        List<List<String>> invalidThumprintLists = [
            [ ],
            [ '' ],
            [ '0' ],
            [
                '0' * 256
            ],
        ]
        invalidThumprintLists.each { List<String> invalidThumbprintList ->
          try {
            N4j.print( "Updating with invalid thumbprint list for provider: ${pathProviderArn} ${invalidThumbprintList}" )
            updateOpenIDConnectProviderThumbprint( new UpdateOpenIDConnectProviderThumbprintRequest(
                openIDConnectProviderArn: pathProviderArn,
                thumbprintList: invalidThumbprintList
            ) )
            Assert.assertTrue('Expected thumbprint update to fail', false)
          } catch( AmazonServiceException e ) {
            N4j.print e.toString( )
            Assert.assertTrue("Expected status code 400, but was: ${e.statusCode}", e.statusCode == 400)
            Assert.assertTrue("Expected error code ValidationError, but was: ${e.errorCode}", e.errorCode == 'ValidationError')
          }
        }

        N4j.print( "Adding client id 'a' for provider: ${pathProviderArn}" )
        addClientIDToOpenIDConnectProvider( new AddClientIDToOpenIDConnectProviderRequest(
            openIDConnectProviderArn: pathProviderArn,
            clientID: 'a'
        ) )

        N4j.print( "Adding client id 'b' for provider: ${pathProviderArn}" )
        addClientIDToOpenIDConnectProvider( new AddClientIDToOpenIDConnectProviderRequest(
            openIDConnectProviderArn: pathProviderArn,
            clientID: 'b'
        ) )

        N4j.print( "Adding duplicate client id 'b' for provider: ${pathProviderArn}" )
        addClientIDToOpenIDConnectProvider( new AddClientIDToOpenIDConnectProviderRequest(
            openIDConnectProviderArn: pathProviderArn,
            clientID: 'b'
        ) )

        List<String> invalidClientIds = [
            '',
            'a' * 256
        ]
        invalidClientIds.each { String invalidClientId ->
          try {
            N4j.print( "Adding invalid client id for provider: ${pathProviderArn} ${invalidClientId}" )
            addClientIDToOpenIDConnectProvider( new AddClientIDToOpenIDConnectProviderRequest(
                openIDConnectProviderArn: pathProviderArn,
                clientID: invalidClientId
            ) )
            Assert.assertTrue('Expected adding invalid client id to fail', false)
          } catch( AmazonServiceException e ) {
            N4j.print e.toString( )
            Assert.assertTrue("Expected status code 400, but was: ${e.statusCode}", e.statusCode == 400)
            Assert.assertTrue("Expected error code ValidationError, but was: ${e.errorCode}", e.errorCode == 'ValidationError')
          }
        }

        N4j.print( "Removing client id 'a' for provider: ${pathProviderArn}" )
        removeClientIDFromOpenIDConnectProvider( new RemoveClientIDFromOpenIDConnectProviderRequest(
            openIDConnectProviderArn: pathProviderArn,
            clientID: 'a'
        ) )

        N4j.print( "Removing unknown client id 'c' for provider: ${pathProviderArn}" )
        removeClientIDFromOpenIDConnectProvider( new RemoveClientIDFromOpenIDConnectProviderRequest(
            openIDConnectProviderArn: pathProviderArn,
            clientID: 'c'
        ) )

        N4j.print "Getting provider to check updates: ${pathProviderArn}"
        getOpenIDConnectProvider( new GetOpenIDConnectProviderRequest(
            openIDConnectProviderArn: pathProviderArn
        ) ).with {
          Assert.assertTrue("Expected thumbprint", thumbprintList != null && thumbprintList.size() == 1)
          Assert.assertTrue("Expected thumbprint ${'1' * 40}, but was: ${thumbprintList[0]}", thumbprintList[0] == ('1' * 40))
          Assert.assertTrue("Expected client id", clientIDList != null && clientIDList.size() == 1)
          Assert.assertTrue("Expected client id 'b', but was: ${clientIDList[0]}", clientIDList[0] == 'b')
        }

        pathProviderArn
      }

      // Create an IAM user
      String userName = "${namePrefix}user-1"
      AWSCredentialsProvider userCreds = getIamClient( ).with {
        N4j.print "Creating user ${userName}"
        createUser( new CreateUserRequest(
            path: '/',
            userName: userName
        ) )
        cleanupTasks.add{
          N4j.print "Deleting user ${userName}"
          deleteUser( new DeleteUserRequest( userName: userName ) )
        }
        createAccessKey( new CreateAccessKeyRequest(
            userName: userName
        ) ).with {
          accessKey.with {
            cleanupTasks.add{
              N4j.print "Deleting access key ${accessKeyId} for user ${userName}"
              deleteAccessKey( new DeleteAccessKeyRequest( userName: userName, accessKeyId: accessKeyId ) )
            }
            new AWSStaticCredentialsProvider( new BasicAWSCredentials( accessKeyId, secretAccessKey ) )
          }
        }
      }

      int sleepSeconds = 15
      N4j.print "Waiting ${sleepSeconds} seconds for credentials to be recognised"
      sleep sleepSeconds * 1000

      getIamClient( userCreds ).with {
        N4j.print "Verifying access denied for user without permissions"
        List<Closure<?>> closureList = [
            {
              N4j.print "Action AddClientIDToOpenIDConnectProvider"
              addClientIDToOpenIDConnectProvider( new AddClientIDToOpenIDConnectProviderRequest(
                  openIDConnectProviderArn: openIdConnectProviderArn,
                  clientID: 'b'
              ) )
              void
            },
            {
              N4j.print "Action CreateOpenIDConnectProvider"
              createOpenIDConnectProvider( new CreateOpenIDConnectProviderRequest( url: 'https://auth.test.com/user', thumbprintList: [ '0' * 40 ] ) )
              void
            },
            {
              N4j.print "Action DeleteOpenIDConnectProvider"
              deleteOpenIDConnectProvider( new DeleteOpenIDConnectProviderRequest( openIDConnectProviderArn: openIdConnectProviderArn ) )
              void
            },
            {
              N4j.print "Action GetOpenIDConnectProvider"
              getOpenIDConnectProvider( new GetOpenIDConnectProviderRequest( openIDConnectProviderArn: openIdConnectProviderArn ) )
              void
            },
            {
              N4j.print "Action ListOpenIDConnectProviders"
              listOpenIDConnectProviders( )
              void
            },
            {
              N4j.print "Action RemoveClientIDFromOpenIDConnectProvider"
              removeClientIDFromOpenIDConnectProvider( new RemoveClientIDFromOpenIDConnectProviderRequest(
                  openIDConnectProviderArn: openIdConnectProviderArn,
                  clientID: 'c'
              ) )
              void
            },
            {
              N4j.print "Action UpdateOpenIDConnectProviderThumbprint"
              updateOpenIDConnectProviderThumbprint( new UpdateOpenIDConnectProviderThumbprintRequest(
                  openIDConnectProviderArn: openIdConnectProviderArn,
                  thumbprintList: [ '2' * 40 ]
              ) )
              void
            },
        ]
        closureList.each {
          try {
            it.call( )
          } catch( AmazonServiceException e ) {
            N4j.print e.toString( )
            Assert.assertTrue("Expected status code 403, but was: ${e.statusCode}", e.statusCode == 403)
            Assert.assertTrue("Expected error code AccessDenied, but was: ${e.errorCode}", e.errorCode == 'AccessDenied')
          }
        }
      }

      getIamClient( ).with {
        final String policyName = 'openid-connect-provider-policy'
        N4j.print "Creating policy ${policyName} for user ${userName}"
        putUserPolicy( new PutUserPolicyRequest(
            userName: userName,
            policyName: policyName,
            policyDocument: '''\
            {
              "Statement": [
                {
                  "Action": [
                    "iam:*OpenIDConnectProvider*"
                  ],
                  "Effect": "Allow",
                  "Resource": "*"
                }
              ]
            }
            '''.stripIndent( )
        ) )
        cleanupTasks.add{
          N4j.print( "Deleting user ${userName} policy ${policyName}" )
          deleteUserPolicy( new DeleteUserPolicyRequest(
              userName: userName,
              policyName: policyName
          ) )
        }
      }

      N4j.print "Waiting ${sleepSeconds} seconds after policy update"
      sleep sleepSeconds * 1000

      getIamClient( userCreds ).with {
        N4j.print "Creating provider as user ${userName}"
        final String userProviderArn = createOpenIDConnectProvider( new CreateOpenIDConnectProviderRequest(
            url: 'https://auth.test.com/user',
            thumbprintList: [ '0' * 40 ]
        ) ).with {
          openIDConnectProviderArn
        }
        N4j.print "Created provider as user ${userName} ${userProviderArn}"
        cleanupTasks.add{
          N4j.print "Deleting provider as user"
          deleteOpenIDConnectProvider( new DeleteOpenIDConnectProviderRequest( openIDConnectProviderArn: userProviderArn ) )
        }

        N4j.print "Adding client 'a' to user ${userName} provider ${userProviderArn}"
        addClientIDToOpenIDConnectProvider( new AddClientIDToOpenIDConnectProviderRequest(
            openIDConnectProviderArn: userProviderArn,
            clientID: 'a'
        ) )

        N4j.print "Adding client 'b' to user ${userName} provider ${userProviderArn}"
        addClientIDToOpenIDConnectProvider( new AddClientIDToOpenIDConnectProviderRequest(
            openIDConnectProviderArn: userProviderArn,
            clientID: 'b'
        ) )

        N4j.print "Removing client 'b' from user ${userName} provider ${userProviderArn}"
        removeClientIDFromOpenIDConnectProvider( new RemoveClientIDFromOpenIDConnectProviderRequest(
            openIDConnectProviderArn: userProviderArn,
            clientID: 'b'
        ) )

        N4j.print "Updating thumbprint for user ${userName} provider ${userProviderArn}"
        updateOpenIDConnectProviderThumbprint( new UpdateOpenIDConnectProviderThumbprintRequest(
            openIDConnectProviderArn: userProviderArn,
            thumbprintList: [ '3' * 40 ]
        ) )

        N4j.print "Getting user ${userName} provider ${userProviderArn}"
        getOpenIDConnectProvider( new GetOpenIDConnectProviderRequest( openIDConnectProviderArn: userProviderArn ) ).with {
          Assert.assertTrue("Expected url", url != null)
          Assert.assertTrue("Expected thumbprint", thumbprintList != null && thumbprintList.size() == 1)
          Assert.assertTrue("Expected thumbprint ${'3' * 40}, but was: ${thumbprintList[0]}", thumbprintList[0] == ('3' * 40))
          Assert.assertTrue("Expected client id", clientIDList != null && clientIDList.size() == 1)
          Assert.assertTrue("Expected client id 'a', but was: ${clientIDList[0]}", clientIDList[0] == 'a')
        }

        N4j.print "Listing providers"
        listOpenIDConnectProviders( ).with {
          int found = openIDConnectProviderList?.findAll{ it.arn == userProviderArn }?.size( ) ?: 0
          Assert.assertTrue("Expected provider listed once: ${userProviderArn}", found == 1)
        }

        N4j.print "Deleting provider as user"
        deleteOpenIDConnectProvider( new DeleteOpenIDConnectProviderRequest( openIDConnectProviderArn: userProviderArn ) )

        N4j.print "Listing providers to verify delete"
        listOpenIDConnectProviders( ).with {
          int found = openIDConnectProviderList?.findAll{ it.arn == userProviderArn }?.size( ) ?: 0
          Assert.assertTrue("Expected provider not listed: ${userProviderArn}", found == 0)
        }
      }

      N4j.print "Test complete"
    } finally {
      // Attempt to clean up anything we created
      cleanupTasks.reverseEach { Runnable cleanupTask ->
        try {
          cleanupTask.run()
        } catch ( NoSuchEntityException e ) {
          N4j.print "Entity not found during cleanup."
        } catch ( AmazonServiceException e ) {
          N4j.print "Service error during cleanup; code: ${e.errorCode}, message: ${e.message}"
        } catch ( Exception e ) {
          e.printStackTrace()
        }
      }
    }
  }
}

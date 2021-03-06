package com.eucalyptus.tests.awssdk

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model.AmazonCloudFormationException
import com.amazonaws.services.cloudformation.model.CreateStackRequest
import com.amazonaws.services.cloudformation.model.DeleteStackRequest
import com.amazonaws.services.cloudformation.model.DescribeStackEventsRequest
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest
import com.amazonaws.services.cloudformation.model.Parameter
import com.amazonaws.services.cloudformation.model.Stack
import com.amazonaws.services.cloudwatch.AmazonCloudWatch
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClient
import com.amazonaws.services.cloudwatch.model.DimensionFilter
import com.amazonaws.services.cloudwatch.model.ListMetricsRequest
import com.amazonaws.services.ec2.model.AccountAttribute
import com.amazonaws.services.ec2.model.DescribeAddressesRequest
import com.amazonaws.services.ec2.model.DescribeInternetGatewaysRequest
import com.amazonaws.services.ec2.model.DescribeNatGatewaysRequest
import com.amazonaws.services.ec2.model.DescribeNetworkAclsRequest
import com.amazonaws.services.ec2.model.DescribeRouteTablesRequest
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest
import com.amazonaws.services.ec2.model.DescribeSubnetsRequest
import com.amazonaws.services.ec2.model.DescribeTagsRequest
import com.amazonaws.services.ec2.model.DescribeVpcsRequest
import com.amazonaws.services.route53.model.GetHostedZoneRequest
import com.amazonaws.services.route53.model.ListResourceRecordSetsRequest
import com.amazonaws.services.route53.model.ListTagsForResourceRequest
import com.amazonaws.services.simpleworkflow.model.DomainDeprecatedException
import com.amazonaws.services.simpleworkflow.model.TypeDeprecatedException
import com.github.sjones4.youcan.youserv.YouServ
import com.github.sjones4.youcan.youserv.YouServClient
import com.github.sjones4.youcan.youserv.model.DescribeServicesRequest
import com.github.sjones4.youcan.youserv.model.Filter
import org.junit.AfterClass
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test

import javax.naming.Context
import javax.naming.NameNotFoundException
import javax.naming.directory.Attributes
import javax.naming.directory.DirContext
import javax.naming.directory.InitialDirContext

import static com.eucalyptus.tests.awssdk.N4j.SERVICES_ENDPOINT

/**
 *
 */
class TestCFTemplatesFull {

  private static String testAcct
  private static String testAcctId
  private static AWSCredentialsProvider testAcctAdminCredentials
  private static AmazonCloudFormation cfClient
  private static AmazonCloudWatch cwClient

  private static AmazonCloudFormation getCloudFormationClient( final AWSCredentialsProvider credentials ) {
    AmazonCloudFormationClient.builder( )
        .withCredentials( credentials )
        .withEndpointConfiguration( new AwsClientBuilder.EndpointConfiguration( N4j.CF_ENDPOINT, 'eucalyptus' ) )
        .build()
  }

  private static AmazonCloudWatch getCloudWatchClient(final AWSCredentialsProvider credentials ) {
    AmazonCloudWatchClient.builder( )
        .withCredentials( credentials )
        .withEndpointConfiguration( new AwsClientBuilder.EndpointConfiguration( N4j.CW_ENDPOINT, 'eucalyptus' ) )
        .build()
  }

  @BeforeClass
  static void init( ){
    N4j.testInfo( TestCFTemplatesFull.simpleName )
    N4j.getCloudInfo( )
    testAcct = "${N4j.NAME_PREFIX}cf-templates"
    testAcctId = N4j.createAccount( testAcct )
    testAcctAdminCredentials = new AWSStaticCredentialsProvider( N4j.getUserCreds( testAcct, 'admin' ) )
    cfClient = getCloudFormationClient( testAcctAdminCredentials )
    cwClient = getCloudWatchClient( testAcctAdminCredentials )
  }

  @AfterClass
  static void cleanup( ) {
    if ( cfClient ) cfClient.shutdown( )
    if ( cwClient ) cwClient.shutdown( )
    N4j.deleteAccount( testAcct )
  }

  private YouServ getServicesClient(final AWSCredentialsProvider credentials ) {
    YouServClient youServ = new YouServClient( credentials )
    youServ.setEndpoint( SERVICES_ENDPOINT )
    youServ
  }

  private Set<String> getDnsHosts( final YouServ youServ ) {
    youServ.describeServices( new DescribeServicesRequest(
        filters: [
            new Filter(
                name: 'service-type',
                values: [ 'dns' ]
            )
        ]
    ) ).with{
      serviceStatuses.collect{ serviceStatus ->
        URI.create( serviceStatus.serviceId.uri ).host
      } as Set<String>
    }
  }

  private String lookup( String name, Set<String> dnsServers ) {
    lookup( name, 'A', dnsServers)
  }

  private String lookup( String name, String type, Set<String> dnsServers ) {
    final Hashtable<String,String> env = new Hashtable<>()
    env.put( Context.INITIAL_CONTEXT_FACTORY, 'com.sun.jndi.dns.DnsContextFactory' )
    env.put( Context.PROVIDER_URL, dnsServers.collect{ ip -> "dns://${ip}/" }.join( ' ' ) )
    env.put( Context.AUTHORITATIVE, 'true' )
    final DirContext ictx = new InitialDirContext( env )
    try {
      final Attributes attrs = ictx.getAttributes(name, [type.toUpperCase()] as String[])
      final String value = attrs.get(type.toLowerCase())?.get()
      return value
    } catch (NameNotFoundException ex) {
      return null
    } finally {
      ictx.close()
    }
  }

  /**
   * Test for YAML template format
   */
  @Test
  void testYamlSyntaxTemplate( ) {
    stackCreateDelete( 'syntax' )
  }

  /**
   * Test for YAML short form intrinsic functions
   */
  @Test
  void testYamlShortIntrinsicsTemplate( ) {
    stackCreateDelete( 'shortform', ['CAPABILITY_NAMED_IAM'] )
  }

  /**
   * Test for ec2 metadata (no instances)
   */
  @Test
  void testEc2MetadataTemplate( ) {
    stackCreateDelete( 'ec2_meta' )
  }

  /**
   * Test for ec2 metadata with yaml template (no instances)
   */
  @Test
  void testEc2MetadataYamlTemplate( ) {
    stackCreateDelete( 'ec2_meta_y', ['CAPABILITY_IAM'], [ 'ImageId': N4j.IMAGE_ID, 'InstanceType': N4j.INSTANCE_TYPE ] )
  }

  /**
   * Test for ec2 cfn-signal
   */
  @Test
  void testEc2SignalResourceTemplate( ) {
    stackCreateDelete( 'ec2_signal_resource', [ ], [ 'ImageId': N4j.IMAGE_ID, 'InstanceType': N4j.INSTANCE_TYPE ] )
  }

  /**
   * Test basic IAM resources
   */
  @Test
  void testIamTemplate( ) {
    stackCreateDelete( 'iam', ['CAPABILITY_IAM'] )
    try {
      stackCreateDelete( 'iam_names' )
      Assert.fail('Expected failure due to missing CAPABILITY_IAM')
    } catch( AmazonCloudFormationException cfe ) {
      N4j.print( "Create failed with exception, verifying error code/status: ${cfe}" )
      Assert.assertEquals('Error code', 'InsufficientCapabilities', cfe.errorCode )
      Assert.assertEquals('Status code', 400, cfe.statusCode )
    }
  }

  /**
   * Test IAM resources with specified names
   */
  @Test
  void testIamNamesTemplate( ) {
    // CAPABILITY_IAM is not needed here but should be allowed
    stackCreateDelete( 'iam_names', ['CAPABILITY_IAM', 'CAPABILITY_NAMED_IAM'] )
    try {
      stackCreateDelete( 'iam_names', ['CAPABILITY_IAM'] )
      Assert.fail('Expected failure due to missing CAPABILITY_NAMED_IAM')
    } catch( AmazonCloudFormationException cfe ) {
      N4j.print( "Create failed with exception, verifying error code/status: ${cfe}" )
      Assert.assertEquals('Error code', 'InsufficientCapabilities', cfe.errorCode )
      Assert.assertEquals('Status code', 400, cfe.statusCode )
    }
  }

  /**
   * Test IAM template with managed policies / policy attachment
   */
  @Test
  void testIamManagedPoliciesTemplate( ) {
    // CAPABILITY_NAMED_IAM not needed but used in place of CAPABILITY_IAM
    stackCreateDelete( 'iam_managed_policies', ['CAPABILITY_NAMED_IAM'] )
  }

  /**
   * Test for instance with many attached volumes
   */
  @Test
  void testInstanceVolumesTemplate( ) {
    stackCreateDelete( 'instance_volumes', [ ], [ 'ImageId': N4j.IMAGE_ID, 'InstanceType': N4j.INSTANCE_TYPE ] )
  }

  /**
   * Test for s3 buckets
   */
  @Test
  void testS3BucketsTemplate( ) {
    stackCreateDelete( 's3_buckets' )
  }

  /**
   * Test for s3 bucket policy
   */
  @Test
  void testS3BucketPolicyTemplate( ) {
    stackCreateDelete( 's3_bucket_policy' )
  }

  /**
   * Test for sqs resources
   */
  @Test
  void testSQSTemplate( ) {
    stackCreateDelete( 'sqs' )
  }

  /**
   * Test for autoscaling, cloudwatch, and elb
   */
  @Test
  void testTrinityTemplate( ) {
    String elbAccountId = getServicesClient(testAcctAdminCredentials).with{
      describeServices(new DescribeServicesRequest(
          filters: [ new Filter( name: 'service-type', values: ['loadbalancing'] ) ]
      ) ).with {
        serviceStatuses?.getAt(0)?.serviceAccounts?.getAt(0)?.number
      }
    };
    Assert.assertNotNull('Elastic load balancing account', elbAccountId )
    stackCreateDelete( 'trinity', [ ], [ 'Image': N4j.IMAGE_ID, 'InstanceType': N4j.INSTANCE_TYPE, 'ElbAccountId': elbAccountId ] ) { Stack stack ->
      String urlText = stack?.outputs?.getAt( 0 )?.outputValue
      Assert.assertNotNull( 'stack url output', urlText )

      String elbName = stack?.outputs?.getAt( 1 )?.outputValue
      Assert.assertNotNull( 'stack elb name output', elbName )

      String asgName = stack?.outputs?.getAt( 2 )?.outputValue
      Assert.assertNotNull( 'stack asg name output', asgName )

      String bucketName = stack?.outputs?.getAt( 3 )?.outputValue
      Assert.assertNotNull( 'stack bucket name output', bucketName )

      N4j.getS3Client( testAcctAdminCredentials, N4j.S3_ENDPOINT ).with {
        String testFileKey = "AWSLogs/${testAcctId}/ELBAccessLogTestFile"
        N4j.print( "Verifying access log test file present: ${testFileKey}" )
        String content = getObjectAsString( bucketName, testFileKey )
        N4j.print( "ELBAccessLogTestFile contents: ${content}" )
        Assert.assertNotNull('ELBAccessLogTestFile content', content )

        N4j.print( "Deleting access log test file: ${testFileKey}" )
        deleteObject( bucketName, testFileKey )
      }

      URL url = new URL( urlText )
      String balancerHost = url.host

      N4j.print( "Resolving load balancer host ${balancerHost}" )
      Set<String> dnsHosts = getDnsHosts(getServicesClient(testAcctAdminCredentials))
      String balancerIp = null
      ( 1..60 ).find {
        if ( it > 1 ) sleep 5000
        balancerIp = lookup(balancerHost, dnsHosts)
      }
      Assert.assertNotNull('Expected ip for load balancer', balancerIp)
      url = new URL( urlText.replace( balancerHost, balancerIp ) )
      N4j.print( "Resolved load balancer host ${balancerHost} to ${balancerIp}, url is ${url}" )

      Object foundResponse = ( 1..60 ).find{
        if ( it > 1 ) N4j.sleep 5
        N4j.print( "Attempting request via elb ${it}" )
        try {
          String balancerResponse =
              url.getText( connectTimeout: 1000, readTimeout: 1000, useCaches: false, allowUserInteraction: false )
          Assert.assertTrue(
              "Expected balancer response Hello, but was: ${balancerResponse}",
              'Hello' == balancerResponse)
          balancerResponse
        } catch ( e ) {
          N4j.print( e.toString( ) )
          null
        }
      }
      Assert.assertNotNull('Expected response from load balancer', foundResponse )

      N4j.print( 'Verifying route53 alias for elb' )
      String balancerIpFromAlias = lookup( 'elb.trinity.eucalyptus.internal', dnsHosts )
      Assert.assertEquals( 'ELB ip from alias', balancerIp, balancerIpFromAlias )

      N4j.print( 'Verifying load balancer cookie present' )
      String setCookieHeader = url.openConnection( ).getHeaderField( 'Set-Cookie' )
      N4j.print( "Set-Cookie: ${setCookieHeader}" )
      Assert.assertNotNull('Expected cookie header', setCookieHeader )

      N4j.print( 'Verifying cloudwatch metrics for load balancer' )
      Object foundMetric = ( 1..40 ).find {
        if ( it > 1 ) N4j.sleep 15
        N4j.print( "Listing metrics for elb ${it}" )
        cwClient.listMetrics(new ListMetricsRequest(
            namespace: 'AWS/ELB',
            metricName: 'HealthyHostCount',
            dimensions: [
                new DimensionFilter(
                  name: 'LoadBalancerName',
                  value: elbName
                )
            ]
        )).with {
          metrics?.getAt(0)?.metricName
        }
      }
      Assert.assertNotNull('Expected cloudwatch metric for load balancer', foundMetric )

//TODO enable when MetricsCollection supported for auto scaling group resource type
//      // "MetricsCollection" : [ {
//      //   "Granularity": "1Minute",
//      //   "Metrics": [ "GroupInServiceInstances" ]
//      // } ]
//      N4j.print( 'Verifying cloudwatch metrics for auto scaling group' )
//      Object foundGroupMetric = ( 1..40 ).find {
//        if ( it > 1 ) N4j.sleep 15
//        N4j.print( "Listing metrics for auto scaling group ${it}" )
//        cwClient.listMetrics(new ListMetricsRequest(
//            namespace: 'AWS/AutoScaling',
//            metricName: 'GroupInServiceInstances',
//            dimensions: [
//                new DimensionFilter(
//                    name: 'AutoScalingGroupName',
//                    value: asgName
//                )
//            ]
//        )).with {
//          metrics?.getAt(0)?.metricName
//        }
//      }
//      Assert.assertNotNull('Expected cloudwatch metric for auto scaling group', foundGroupMetric )

      null
    }
  }

  private void assumeElbv2Available( ) {
    N4j.print( 'Checking for ELBv2 support' )
    N4j.assumeThat( N4j.isAtLeastEucalyptusVersion('6.0.0'), 'ELBv2 available' )
    N4j.print( 'Checking for VPC support' )
    final boolean vpcAvailable = N4j.ec2.describeAccountAttributes( ).with {
      accountAttributes.find{ AccountAttribute accountAttribute ->
        accountAttribute.attributeName == 'supported-platforms'
      }?.attributeValues*.attributeValue.contains( 'VPC' )
    }
    N4j.print( "VPC supported: ${vpcAvailable}" )
    N4j.assumeThat( vpcAvailable, 'VPC is a supported platform' )
  }

  /**
   * Test for ELBv2 with single instance target
   */
  @Test
  void testElbv2Template( ) {
    assumeElbv2Available( )
    stackCreateDelete( 'elbv2', [ ], [ 'ImageId': N4j.IMAGE_ID, 'InstanceType': N4j.INSTANCE_TYPE ] ) { Stack stack ->

      String lbArn = stack?.outputs?.getAt( 0 )?.outputValue
      Assert.assertNotNull( 'stack load balancer arn output', lbArn )

      String tgArn = stack?.outputs?.getAt( 1 )?.outputValue
      Assert.assertNotNull( 'stack target group arn output', tgArn )

      String elbDnsName = stack?.outputs?.getAt( 2 )?.outputValue
      Assert.assertNotNull( 'stack dns name output', elbDnsName )

      URL url = new URL( "http://${elbDnsName}/" )
      String balancerHost = url.host

      N4j.print( "Resolving load balancer host ${balancerHost}" )
      Set<String> dnsHosts = getDnsHosts(getServicesClient(testAcctAdminCredentials))
      String balancerIp = null
      ( 1..60 ).find {
        if ( it > 1 ) sleep 5000
        balancerIp = lookup(balancerHost, dnsHosts)
      }
      Assert.assertNotNull('Expected ip for load balancer', balancerIp)
      url = new URL( "http://${elbDnsName}/".replace( balancerHost, balancerIp ) )
      N4j.print( "Resolved load balancer host ${balancerHost} to ${balancerIp}, url is ${url}" )

      Object foundResponse = ( 1..60 ).find{
        if ( it > 1 ) N4j.sleep 5
        N4j.print( "Attempting request via elb ${it}" )
        try {
          String balancerResponse =
                  url.getText( connectTimeout: 1000, readTimeout: 1000, useCaches: false, allowUserInteraction: false )
          Assert.assertTrue(
                  "Expected balancer response Hello, but was: ${balancerResponse}",
                  'Hello' == balancerResponse)
          balancerResponse
        } catch ( e ) {
          N4j.print( e.toString( ) )
          null
        }
      }
      Assert.assertNotNull('Expected response from load balancer', foundResponse )
      N4j.print( "Got expected response from load balancer" )

      null
    }
  }

  /**
   * Test for ELBv2 with AutoScaling registered target
   */
  @Test
  void testElbv2AutoScalingTemplate( ) {
    assumeElbv2Available( )
    String elbAccountId = getServicesClient(testAcctAdminCredentials).with{
      describeServices(new DescribeServicesRequest(
              filters: [ new Filter( name: 'service-type', values: ['loadbalancingv2'] ) ]
      ) ).with {
        serviceStatuses?.getAt(0)?.serviceAccounts?.getAt(0)?.number
      }
    };
    Assert.assertNotNull('Elastic load balancing v2 account', elbAccountId )
    stackCreateDelete( 'elbv2_autoscaling', [ ], [ 'ImageId': N4j.IMAGE_ID, 'InstanceType': N4j.INSTANCE_TYPE, 'ElbAccountId': elbAccountId ] ) { Stack stack ->


      String lbArn = stack?.outputs?.getAt( 0 )?.outputValue
      Assert.assertNotNull( 'stack load balancer arn output', lbArn )

      String tgArn = stack?.outputs?.getAt( 1 )?.outputValue
      Assert.assertNotNull( 'stack target group arn output', tgArn )

      String elbDnsName = stack?.outputs?.getAt( 2 )?.outputValue
      Assert.assertNotNull( 'stack dns name output', elbDnsName )

      String bucketName = stack?.outputs?.getAt( 3 )?.outputValue
      Assert.assertNotNull( 'stack bucket name output', bucketName )

      URL url = new URL( "http://${elbDnsName}/" )
      String balancerHost = url.host

      N4j.print( "Resolving load balancer host ${balancerHost}" )
      Set<String> dnsHosts = getDnsHosts(getServicesClient(testAcctAdminCredentials))
      String balancerIp = null
      ( 1..60 ).find {
        if ( it > 1 ) sleep 5000
        balancerIp = lookup(balancerHost, dnsHosts)
      }
      Assert.assertNotNull('Expected ip for load balancer', balancerIp)
      url = new URL( "http://${elbDnsName}/".replace( balancerHost, balancerIp ) )
      N4j.print( "Resolved load balancer host ${balancerHost} to ${balancerIp}, url is ${url}" )

      Object foundResponse = ( 1..60 ).find{
        if ( it > 1 ) N4j.sleep 5
        N4j.print( "Attempting request via elb ${it}" )
        try {
          String balancerResponse =
                  url.getText( connectTimeout: 1000, readTimeout: 1000, useCaches: false, allowUserInteraction: false )
          Assert.assertTrue(
                  "Expected balancer response Hello, but was: ${balancerResponse}",
                  'Hello' == balancerResponse)
          balancerResponse
        } catch ( e ) {
          N4j.print( e.toString( ) )
          null
        }
      }
      Assert.assertNotNull('Expected response from load balancer', foundResponse )
      N4j.print( "Got expected response from load balancer" )

      N4j.print( 'Verifying load balancer cookie present' )
      String setCookieHeader = url.openConnection( ).getHeaderField( 'Set-Cookie' )
      N4j.print( "Set-Cookie: ${setCookieHeader}" )
      Assert.assertNotNull('Expected cookie header', setCookieHeader )

      null
    }
  }

  /**
   * Test for vpc metadata (no instances so good with any network mode)
   */
  @Test
  void testVpcMetadataTemplate( ) {
    stackCreateDelete( 'vpc_meta' )
  }

  /**
   * Test for vpc metadata requiring vpc platform
   */
  @Test
  void testVpcMetadataRequiringVpcTemplate( ) {
    N4j.print( 'Checking for VPC support' )
    final boolean vpcAvailable = N4j.ec2.describeAccountAttributes( ).with {
      accountAttributes.find{ AccountAttribute accountAttribute ->
        accountAttribute.attributeName == 'supported-platforms'
      }?.attributeValues*.attributeValue.contains( 'VPC' )
    }
    N4j.print( "VPC supported: ${vpcAvailable}" )
    N4j.assumeThat( vpcAvailable, 'VPC is a supported platform' )
    stackCreateDelete( 'vpc_meta_vpc_platform', [], [:] ) {  Stack stack ->
      String vpcId = stack?.outputs?.getAt( 0 )?.getOutputValue();
      String allocationId = stack?.outputs?.getAt( 6 )?.getOutputValue();
      N4j.print( "Verifying tags for vpc stack : ${vpcId}" )

      N4j.getEc2Client( testAcctAdminCredentials, N4j.EC2_ENDPOINT ).with {
        // verify tags created
        describeTags( new DescribeTagsRequest(
            filters: [
                new com.amazonaws.services.ec2.model.Filter( name: 'key', values: ['vpccftest'] )
            ]
        ) ).with {
          N4j.print( "Got tags: ${tags}" )
          Assert.assertEquals( 'Tag count', 8, tags?.size()?:0 )
        }

        // verify tags by resource
        describeVpcs( new DescribeVpcsRequest(
            filters: [
                new com.amazonaws.services.ec2.model.Filter( name: 'vpc-id', values: [ vpcId ] )
            ]
        ) ).with {
          Assert.assertEquals("Vpc count", 1, vpcs?.size()?:0 )
          vpcs?.getAt(0)?.with {
            N4j.print( "Got tags: ${tags}" )
            Assert.assertNotNull('Vpc has tag', tags )
            Assert.assertEquals( 'Vpc tag count', 4, tags.size() )
          }
        }

        describeInternetGateways( new DescribeInternetGatewaysRequest(
            filters: [
                new com.amazonaws.services.ec2.model.Filter( name: 'attachment.vpc-id', values: [ vpcId ] )
            ]
        ) ).with {
          Assert.assertEquals("Internet gateway count", 1, internetGateways?.size()?:0 )
          internetGateways?.getAt(0)?.with {
            N4j.print( "Got tags: ${tags}" )
            Assert.assertNotNull('Internet gateway has tag', tags )
            Assert.assertEquals( 'Internet gateway tag count', 4, tags.size() )
          }
        }

        describeAddresses( new DescribeAddressesRequest(
            filters: [
                new com.amazonaws.services.ec2.model.Filter( name: 'allocation-id', values: [ allocationId ] )
            ]
        ) ).with {
          Assert.assertEquals("Elastic IP count", 1, addresses?.size()?:0 )
          addresses?.getAt(0)?.with {
            N4j.print( "Got tags: ${tags}" )
            Assert.assertNotNull('Elastic IP has tag', tags )
            Assert.assertEquals( 'Elastic IP tag count', 4, tags.size() )
          }
        }

        describeNatGateways( new DescribeNatGatewaysRequest(
            filter: [
                new com.amazonaws.services.ec2.model.Filter( name: 'vpc-id', values: [ vpcId ] )
            ]
        ) ).with {
          Assert.assertEquals("NAT gateway count", 1, natGateways?.size()?:0 )
          natGateways?.getAt(0)?.with {
            N4j.print( "Got tags: ${tags}" )
            Assert.assertNotNull('NAT gateway has tag', tags )
            Assert.assertEquals( 'NAT gateway tag count', 4, tags.size() )
          }
        }

        describeSubnets( new DescribeSubnetsRequest(
            filters: [
                new com.amazonaws.services.ec2.model.Filter( name: 'vpc-id', values: [ vpcId ] )
            ]
        ) ).with {
          Assert.assertEquals("Subnet count", 1, subnets?.size()?:0 )
          subnets?.getAt(0)?.with {
            N4j.print( "Got tags: ${tags}" )
            Assert.assertNotNull('Subnet has tag', tags )
            Assert.assertEquals( 'Subnet tag count', 4, tags.size() )
          }
        }

        describeNetworkAcls( new DescribeNetworkAclsRequest(
            filters: [
                new com.amazonaws.services.ec2.model.Filter( name: 'vpc-id', values: [ vpcId ] ),
                new com.amazonaws.services.ec2.model.Filter( name: 'default', values: [ 'false' ] )
            ]
        ) ).with {
          Assert.assertEquals("Network ACL count", 1, networkAcls?.size()?:0 )
          networkAcls?.getAt(0)?.with {
            N4j.print( "Got entries: ${entries}" )
            Assert.assertNotNull('Network ACL has entries', entries )
            Assert.assertEquals( 'Network ACL entry count', 9, entries.size() )

            N4j.print( "Got tags: ${tags}" )
            Assert.assertNotNull('Network ACL has tag', tags )
            Assert.assertEquals( 'Network ACL tag count', 4, tags.size() )
          }
        }

        describeRouteTables( new DescribeRouteTablesRequest(
            filters: [
                new com.amazonaws.services.ec2.model.Filter( name: 'vpc-id', values: [ vpcId ] ),
                new com.amazonaws.services.ec2.model.Filter( name: 'association.main', values: [ 'false' ] )
            ]
        ) ).with {
          Assert.assertEquals("Route table count", 1, routeTables?.size()?:0 )
          routeTables?.getAt(0)?.with {
            N4j.print( "Got tags: ${tags}" )
            Assert.assertNotNull('Route table has tag', tags )
            Assert.assertEquals( 'Route table tag count', 4, tags.size() )
          }
        }

        describeSecurityGroups( new DescribeSecurityGroupsRequest(
            filters: [
                new com.amazonaws.services.ec2.model.Filter( name: 'vpc-id', values: [ vpcId ] ),
                new com.amazonaws.services.ec2.model.Filter( name: 'ip-permission.from-port', values: [ '22' ] )
            ]
        ) ).with {
          Assert.assertEquals('Security group count', 1, securityGroups?.size()?:0 )
          securityGroups?.getAt(0)?.with {
            N4j.print( "Got tags: ${tags}" )
            Assert.assertNotNull('Security group has tag', tags )
            Assert.assertEquals( 'Security group tag count', 4, tags.size() )
          }
        }
      }

      null
    }
  }

  /**
   * Test for vpc network acls requiring vpc platform
   */
  @Test
  void testVpcNetworkAclsTemplate( ) {
    N4j.print('Checking for VPC support')
    final boolean vpcAvailable = N4j.ec2.describeAccountAttributes().with {
      accountAttributes.find { AccountAttribute accountAttribute ->
        accountAttribute.attributeName == 'supported-platforms'
      }?.attributeValues*.attributeValue.contains('VPC')
    }
    N4j.print("VPC supported: ${vpcAvailable}")
    N4j.assumeThat(vpcAvailable, 'VPC is a supported platform')
    stackCreateDelete('vpc_nacls', [], ['ImageId': N4j.IMAGE_ID, 'InstanceType': N4j.INSTANCE_TYPE]) { Stack stack ->
      String result1 = stack?.outputs?.getAt(0)?.getOutputValue();
      String result2 = stack?.outputs?.getAt(1)?.getOutputValue();

      Assert.assertNotNull('Expected stack output Result1', result1 )
      Assert.assertNotNull('Expected stack output Result2', result2 )

      Assert.assertEquals('Expected stack output Result1', '{"result":"TEST0, PING204, SCTP204, PING103, TCP103, UDP103, SCTP103, TEST101"}', result1 )
      Assert.assertEquals('Expected stack output Result2', '{"result":"TEST0, SCTP204, TCP103, UDP103, SCTP103, PING202, TEST101"}', result2 )

      null
    }
  }

  /**
   * Test for route53 private hosted zone, test should pass with any network mode
   */
  @Test
  void testRoute53PrivateHostedZoneTemplate( ) {
    stackCreateDelete( 'route53_private' )
  }

  /**
   * Test for route53 public hosted zone with default soa/ns records
   */
  @Test
  void testRoute53PublicHostedZoneTemplate( ) {
    stackCreateDelete( 'route53_public_defaults', [], [:], {  Stack stack ->
      String hostedZoneId = stack?.outputs?.getAt( 0 )?.getOutputValue()
      String hostedZoneNameservers = stack?.outputs?.getAt( 1 )?.getOutputValue()

      N4j.print( "Verifying stack for zone : ${hostedZoneId} / ${hostedZoneNameservers}" )
      N4j.getRoute53Client(testAcctAdminCredentials, N4j.ROUTE53_ENDPOINT).with {
        N4j.print( "Verifying hosted zone details : ${hostedZoneId}" )
        getHostedZone(new GetHostedZoneRequest(id: hostedZoneId)).with {
          Assert.assertNotNull('HostedZone', hostedZone)
          Assert.assertEquals('ID', hostedZoneId, hostedZone.id)
          Assert.assertEquals('Name', 'example.com.', hostedZone.name)

          Assert.assertNotNull('HostedZone.HostedZoneConfig', hostedZone.config)
          Assert.assertEquals('Comment', 'Public zone for example.com', hostedZone.config.comment)
          Assert.assertNotNull('PrivateZone', hostedZone.config.privateZone)
          Assert.assertFalse('PrivateZone', hostedZone.config.privateZone)
        }

        N4j.print( "Verifying hosted zone default records (soa/ns) : ${hostedZoneId}" )
        listResourceRecordSets(new ListResourceRecordSetsRequest(hostedZoneId: hostedZoneId)).with {
          Assert.assertNotNull('ResourceRecordSets', resourceRecordSets)
          Assert.assertEquals('ResourceRecordSets count', 10, resourceRecordSets.size())
          resourceRecordSets.each { rrset ->
            if ('SOA'.equals(rrset.type)) {
              Assert.assertEquals('RRSET name', 'example.com.', rrset.name)
              Assert.assertNotNull('RRSET values', rrset.resourceRecords)
              Assert.assertEquals('RRSET values count', 1, rrset.resourceRecords.size())
            } else if ('NS'.equals(rrset.type)) {
              Assert.assertEquals('RRSET name', 'example.com.', rrset.name)
              Assert.assertNotNull('RRSET values', rrset.resourceRecords)
            }
          }
        }

        N4j.print( "Verifying tags for route53 stack : ${hostedZoneId}" )
        Map<String,String> expectedTags = [
            'aws:cloudformation:logical-id': 'HostedZone',
            'aws:cloudformation:stack-name': 'route53-public-defaults',
            'tag-1': 'example.com',
            'tag-2': 'value-2',
            'tag-3': 'value-3',
            'tag-4': 'value-4',
            'tag-5': 'value-5',
        ]
        listTagsForResource(new ListTagsForResourceRequest(
            resourceType: 'hostedzone',
            resourceId: hostedZoneId)).with {
          Assert.assertNotNull('ResourceTagSet', resourceTagSet)
          Assert.assertEquals('ResourceTagSet.resourceType', 'hostedzone', resourceTagSet.resourceType)
          Assert.assertEquals('ResourceTagSet.resourceId', hostedZoneId, resourceTagSet.resourceId)
          Assert.assertNotNull('ResourceTagSet.tags', resourceTagSet.tags)
          Assert.assertEquals('ResourceTagSet.tags count', 8, resourceTagSet.tags.size())
          resourceTagSet.tags.each { tag ->
            if (expectedTags.containsKey(tag.key)) {
              Assert.assertEquals('Tag value', expectedTags.get(tag.key), tag.value)
            }
          }
        }
      }

      Set<String> dnsHosts = getDnsHosts(getServicesClient(testAcctAdminCredentials))
      N4j.waitForIt('zone dns', { time ->
        try {
          lookup('name.example.com', 'A', dnsHosts)
          return true
        } catch (Exception e) {
          return false;
        }
      }, 30000)
      [
          'name.example.com': '10.20.30.41',
          'a1.example.com': '10.20.30.41',
          'a2.example.com': '10.20.30.42',
          'a3.example.com': '10.20.30.43',
          'a4.example.com': '10.20.30.43',
      ].forEach( { String name, String ip ->
        N4j.print( "Looking up name ${name}" )
        String resolvedIp = lookup(name, 'A', dnsHosts)
        Assert.assertNotNull("Expected ip for ${name}", resolvedIp)
        Assert.assertEquals("Resolved ip for ${name}", ip, resolvedIp)
      } )
      [
          'cname.example.com': 'name.example.com.',
      ].forEach( { String cname, String name ->
        N4j.print( "Looking up cname ${cname}" )
        String resolvedName = lookup(cname, 'CNAME', dnsHosts)
        Assert.assertNotNull("Expected name for ${cname}", resolvedName)
        Assert.assertEquals("Resolved name for ${cname}", name, resolvedName)
      } )
      [
          'txt.example.com': 'txtrecord',
          'txtrec.subdomain.example.com': 'txtrec',
      ].forEach( { String name, String value ->
        N4j.print( "Looking up txt record ${name}" )
        String resolvedTxt = lookup(name, 'TXT', dnsHosts)
        Assert.assertNotNull("Expected txt for ${name}", resolvedTxt)
        Assert.assertEquals("Resolved txt for ${name}", value, resolvedTxt)
      } )

      null
    } )
  }

  /**
   * Test for route53 public hosted zone with custom soa/ns records
   */
  @Test
  void testRoute53PublicHostedZoneCustomTemplate( ) {
    stackCreateDelete( 'route53_public_custom' )
  }

  private void stackCreateDelete(
      final String stackTemplateId,
      final List<String> capabilities = [ ],
      final Map<String,String> parameters = [:],
      final Closure<Object> createdCallback = null
  ) {
    final String stackName = stackTemplateId.replace('_','-')
    URL templateUrl = TestCFTemplatesFull.getResource( "cf_template_${stackTemplateId}.json" )
    if ( templateUrl == null ) {
      templateUrl = TestCFTemplatesFull.getResource( "cf_template_${stackTemplateId}.yaml" )
    }
    final String stackTemplate = templateUrl.getText("utf-8")
    final List<Runnable> cleanupTasks = [] as List<Runnable>
    try {
      cfClient.with{
        N4j.print( "Creating test stack: ${stackName}" )
        String stackId = createStack( new CreateStackRequest(
            stackName: stackName,
            capabilities: capabilities,
            parameters: parameters.collect{ new Parameter( parameterKey: it.key, parameterValue: it.value ) },
            templateBody: stackTemplate
        ) ).stackId
        Assert.assertTrue("Expected stack id", stackId != null)
        N4j.print( "Created stack with id: ${stackId}" )
        cleanupTasks.add{
          N4j.print( "Deleting stack: ${stackName}" )
          deleteStack( new DeleteStackRequest( stackName: stackName ) )
        }

        N4j.print( "Waiting for stack ${stackName} creation" )
        String lastStatus = ''
        ( 1..120 ).find{
          N4j.sleep 5
          N4j.print( "Waiting for stack ${stackName} creation, waited ${it*5}s" )
          describeStacks( new DescribeStacksRequest(
              stackName: stackId
          ) ).with {
            stacks?.getAt( 0 )?.with { Stack stack ->
              if ( stack.stackId == stackId ) {
                lastStatus = stack.stackStatus
                if ( lastStatus == 'CREATE_COMPLETE' ) {
                  lastStatus
                } else if ( lastStatus == 'CREATE_IN_PROGRESS' ) {
                  null
                } else {
                  describeStackEvents( new DescribeStackEventsRequest(
                      stackName: stackId
                  ) ).with {
                    stackEvents.each { event ->
                      N4j.print( event.toString( ) )
                    }
                  }
                  Assert.fail( "Unexpected status ${lastStatus}" )
                  null
                }
              } else {
                Assert.fail( "Unexpected stack ${stack.stackId}" )
                null
              }
            }
          }
        }
        Assert.assertEquals( 'Stack status', 'CREATE_COMPLETE', lastStatus )

        N4j.print( "Stack outputs ${stackName}[${stackId}]" )
        describeStacks( new DescribeStacksRequest(
            stackName: stackId
        ) ).with {
          stacks?.getAt( 0 )?.with { stack ->
            outputs?.each { output ->
              N4j.print( output.toString( ) )
            }
            createdCallback?.call( stack )
          }
        }

        N4j.print( "Deleting stack ${stackName}[${stackId}]" )
        deleteStack( new DeleteStackRequest(
            stackName: stackId
        ) )

        N4j.print( "Waiting for stack ${stackName} deletion" )
        ( 1..120 ).find{
          N4j.sleep 5
          N4j.print( "Waiting for stack ${stackName} deletion, waited ${it*5}s" )
          describeStacks( new DescribeStacksRequest(
              stackName: stackId
          ) ).with {
            stacks?.getAt( 0 )?.with { Stack stack ->
              if ( stack.stackId == stackId ) {
                lastStatus = stack.stackStatus
                if ( lastStatus == 'DELETE_COMPLETE' ) {
                  lastStatus
                } else if ( lastStatus == 'CREATE_COMPLETE' ) {
                  N4j.print( "Retrying delete stack ${stackName}[${stackId}]" )
                  deleteStack( new DeleteStackRequest(
                      stackName: stackId
                  ) )
                  null
                } else if ( lastStatus == 'DELETE_IN_PROGRESS' ) {
                  null
                } else {
                  describeStackEvents( new DescribeStackEventsRequest(
                    stackName: stackId
                  ) ).with {
                    stackEvents.each { event ->
                      N4j.print( event.toString( ) )
                    }
                  }
                  Assert.fail( "Unexpected status ${lastStatus}" )
                  null
                }
              } else {
                Assert.fail( "Unexpected stack ${stack.stackId}" )
                null
              }
            }
          }
        }
        Assert.assertEquals( 'Stack status', 'DELETE_COMPLETE', lastStatus )
      }
    } finally {
      // Attempt to clean up anything we created
      N4j.print( "Running clean up tasks for ${stackName}" )
      cleanupTasks.reverseEach { Runnable cleanupTask ->
        try {
          cleanupTask.run()
        } catch ( DomainDeprecatedException e ) {
          N4j.print( e.message )
        } catch ( TypeDeprecatedException e ) {
          N4j.print( e.message )
        } catch ( Exception e ) {
          e.printStackTrace()
        }
      }
    }
  }
}

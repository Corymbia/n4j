/*************************************************************************
 * Copyright 2009-2016 Eucalyptus Systems, Inc.
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
 ************************************************************************/
package com.eucalyptus.tests.awssdk;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.autoscaling.model.*;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.elasticloadbalancing.model.ConfigureHealthCheckRequest;
import com.amazonaws.services.elasticloadbalancing.model.HealthCheck;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.eucalyptus.tests.awssdk.N4j.*;


/**
 * This application tests ELB monitoring of instance health for auto scaling.
 * <p/>
 * This is verification for the story:
 * <p/>
 * https://eucalyptus.atlassian.net/browse/EUCA-5010
 */
public class TestAutoScalingELBInstanceHealthMonitoring {

    @Test
    public void AutoScalingELBInstanceHealthMonitoringTest() throws Exception {
        testInfo(this.getClass().getSimpleName());
        getCloudInfo();

        final List<Runnable> cleanupTasks = new ArrayList<Runnable>();
        try {
            // Generate a load balancer to use
            final String loadBalancerName = NAME_PREFIX + "ELBHealth";
            print("Creating a load balancer for test use: " + loadBalancerName);
            createLoadBalancer(loadBalancerName);
            cleanupTasks.add(new Runnable() {
                @Override
                public void run() {
                    print("Deleting load balancer: " + loadBalancerName);
                    deleteLoadBlancer(loadBalancerName);
                }
            });

            // Create launch configuration
            final String configName = NAME_PREFIX + "ELBInstanceHealthMonitoringTest";
            print("Creating launch configuration: " + configName);
            as.createLaunchConfiguration(new CreateLaunchConfigurationRequest()
                    .withLaunchConfigurationName(configName)
                    .withImageId(IMAGE_ID)
                    .withInstanceType(INSTANCE_TYPE));
            cleanupTasks.add(new Runnable() {
                @Override
                public void run() {
                    print("Deleting launch configuration: " + configName);
                    as.deleteLaunchConfiguration(new DeleteLaunchConfigurationRequest().withLaunchConfigurationName(configName));
                }
            });

            // Create scaling group
            final String groupName = NAME_PREFIX + "ELBInstanceHealthMonitoringTest";
            print("Creating auto scaling group: " + groupName);
            as.createAutoScalingGroup(new CreateAutoScalingGroupRequest()
                    .withAutoScalingGroupName(groupName)
                    .withLaunchConfigurationName(configName)
                    .withLoadBalancerNames(loadBalancerName)
                    .withDesiredCapacity(1)
                    .withMinSize(1)
                    .withMaxSize(1)
                    .withHealthCheckType("ELB")
                    .withHealthCheckGracePeriod(90) // 1 1/2 minutes
                    .withAvailabilityZones(AVAILABILITY_ZONE)
                    .withTerminationPolicies("OldestInstance"));
            cleanupTasks.add(new Runnable() {
                @Override
                public void run() {
                    print("Deleting group: " + groupName);
                    deleteAutoScalingGroup(groupName,true);
                }
            });
            cleanupTasks.add(new Runnable() {
                @Override
                public void run() {
                    final List<String> instanceIds = (List<String>) getInstancesForGroup(groupName, null,true);
                    print("Terminating instances: " + instanceIds);
                    ec2.terminateInstances(new TerminateInstancesRequest().withInstanceIds(instanceIds));
                }
            });

            // Wait for instance to launch
            print("Waiting for instance to launch");
            final long timeout = TimeUnit.MINUTES.toMillis(15);
            final String instanceId = (String) waitForInstances(timeout, 1, groupName,true).get(0);

            print("Waiting for instance to be added to ELB");
            waitForElbInstances(loadBalancerName, TimeUnit.MINUTES.toMillis(15), Arrays.asList(instanceId));
            print("Instance added to ELB");

            // Verify initial health status
            print("Verifying initial instance status");
            verifyInstanceHealthStatus(instanceId, "Healthy");

            // Create bad health check on ELB
            print("Setting up failing health check for instance");
            elb.configureHealthCheck(new ConfigureHealthCheckRequest()
                    .withLoadBalancerName(loadBalancerName)
                    .withHealthCheck(new HealthCheck()
                            .withHealthyThreshold(2)
                            .withUnhealthyThreshold(2)
                            .withInterval(5)
                            .withTimeout(4)
                            .withTarget("HTTP:1023/")
                    )
            );

            // Verify bad health status
            print("Waiting for auto scaling instance health to change : " + instanceId);
            waitForHealthStatus(instanceId, "Unhealthy");

            // Delay to allow for health status to be acted on
            print("Waiting for unhealthy instance replacement : " + instanceId);
            Thread.sleep(TimeUnit.SECONDS.toMillis(30));

            // Wait for replacement instance
            print("Waiting for replacement instance to launch");
            final String replacementInstanceId = (String) waitForInstances(timeout, 1, groupName,true).get(0);
            assertThat(!replacementInstanceId.equals(instanceId), "Instance not replaced");

            // Set desired capacity below minimum exception expected
            print("Setting desired capacity to 0 (below minimum should fail) for group: " + groupName);
            try {
                as.setDesiredCapacity(new SetDesiredCapacityRequest()
                        .withAutoScalingGroupName(groupName)
                        .withDesiredCapacity(0));
                assertThat(false, "Setting Desired Capacity below min should fail");
            } catch (AmazonServiceException e) {
                print("Expected error returned: " + e);
            }

            // Set desired minimum size to zero
            as.updateAutoScalingGroup(new UpdateAutoScalingGroupRequest()
                    .withAutoScalingGroupName(groupName)
                    .withMinSize(0));
            print("Changed Minimum size to Zero");
            // Set desired capacity to zero
            print("Setting desired capacity to 0 for group: " + groupName);
            as.setDesiredCapacity(new SetDesiredCapacityRequest()
                    .withAutoScalingGroupName(groupName)
                    .withDesiredCapacity(0));

            // Wait for instance to terminate
            print("Waiting for instance to terminate");
            waitForInstances(timeout, 0, groupName,true);

            print("Test complete");
        } finally {
            // Attempt to clean up anything we created
            Collections.reverse(cleanupTasks);
            for (final Runnable cleanupTask : cleanupTasks) {
                try {
                    cleanupTask.run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}

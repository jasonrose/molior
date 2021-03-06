/**
 * Copyright (C) 2012 John Trimble <trimblej@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.johntrimble.molior.maven.plugins

import java.util.concurrent.Future

import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.factory.ArtifactFactory
import org.apache.maven.artifact.metadata.ArtifactMetadataSource
import org.apache.maven.artifact.repository.ArtifactRepository
import org.apache.maven.artifact.resolver.ArtifactNotFoundException
import org.apache.maven.artifact.resolver.ArtifactResolutionException
import org.apache.maven.artifact.resolver.ArtifactResolver
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException
import org.apache.maven.artifact.versioning.VersionRange
import org.apache.maven.model.Dependency
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.project.MavenProject

import org.codehaus.gmaven.common.ArtifactItem
import org.codehaus.gmaven.mojo.GroovyMojo
import org.codehaus.plexus.util.FileUtils

import org.jfrog.maven.annomojo.annotations.MojoAggregator
import org.jfrog.maven.annomojo.annotations.MojoParameter
import org.jfrog.maven.annomojo.annotations.MojoGoal
import org.joda.time.Interval;
import org.joda.time.PeriodType;
import org.joda.time.format.PeriodFormat

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.PropertiesCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model.CreateStackRequest
import com.amazonaws.services.cloudformation.model.CreateStackResult
import com.amazonaws.services.cloudformation.model.DeleteStackRequest
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest
import com.amazonaws.services.cloudformation.model.DescribeStacksResult
import com.amazonaws.services.cloudformation.model.ListStacksRequest
import com.amazonaws.services.cloudformation.model.ListStacksResult;
import com.amazonaws.services.cloudformation.model.Parameter
import com.amazonaws.services.cloudformation.model.Stack
import com.amazonaws.services.cloudformation.model.StackStatus
import com.amazonaws.services.cloudformation.model.StackSummary;
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.AssociateAddressRequest
import com.amazonaws.services.ec2.model.DisassociateAddressRequest

/**
*
* @author John Trimble <john.trimble@meltmedia.com>
*/
@MojoAggregator
@MojoGoal('delete-stack')
class DeleteStackMojo extends AbstractMojo {

  @MojoParameter(defaultValue='false')
  public boolean skipMostRecent
  
  @MojoParameter(required=true)
  public String selector
 
  void execute() {
    log.info "Deleting stacks matching filter: ${selector}"
    Filter f = new Filter(selector)
    preProcessStacks(getAllStacks().grep { 
      f(mapifyStack(it)) 
    }).grep {
      !interactive || prompter.prompt("Delete stack '${it.stackName}' created ${getAgeString(it)} ago?", ["Y", "n"], "n") == "Y"
    }.each {
      log.info "Deleting stack: ${it.stackName}"
      cloudFormation.deleteStack new DeleteStackRequest(stackName: it.stackId)
    }
  }
  
  def preProcessStacks(List stacks) {
    stacks = stacks.sort { a, b -> a.creationTime <=> b.creationTime }
    if( skipMostRecent && stacks.size() ) {
      stacks.pop()
    }
    return stacks
  }
  
  List<Stack> getAllStacks() {
    // If the list of requested stacks exceeds some threshold, Amazon will not return the outputs or parameters for 
    // each stack. Since outputs and parameters can be used in filtering, we need to grab each stack individually.
    
    // Issue #4: when there are more than 20 stacks on an account, describeStacks() will no longer work reliably without a
    // stackName parameter. Consequently, we need to use ListStacks instead.
    def stackSummaries = []
    String nextToken = ''
    while( true ) { 
      ListStacksRequest request = new ListStacksRequest()
      .withStackStatusFilters("CREATE_IN_PROGRESS", "CREATE_FAILED",
          "CREATE_COMPLETE", "ROLLBACK_FAILED","ROLLBACK_COMPLETE",
          "DELETE_FAILED", "UPDATE_IN_PROGRESS",
          "UPDATE_COMPLETE_CLEANUP_IN_PROGRESS", "UPDATE_COMPLETE",
          "UPDATE_ROLLBACK_FAILED")
      if( nextToken ) {
        request.nextToken = nextToken
      }
      ListStacksResult result = this.cloudFormation.listStacks(request)
      nextToken = result.nextToken
      stackSummaries.addAll result.stackSummaries
      if( !nextToken ) {
        break
      }
    }
    // Get the complete stack objects.
    stackSummaries
      .collate(3)
      .collect {
        // due to rate limiting
        Thread.sleep 3000
        it.collect{ 
            this.cloudFormation.describeStacksAsync(
              new DescribeStacksRequest(stackName: it.stackId))
          }
          .collect { it.get().stacks }
          .flatten()
      }
      .flatten()
  }
  
  private String getAgeString(Stack s) {
    PeriodFormat.wordBased().print(
      new Interval(s.creationTime.getTime(), java.lang.System.currentTimeMillis())
      .toPeriod(PeriodType.yearDayTime().withMillisRemoved().withMinutesRemoved().withSecondsRemoved()))
  }
}

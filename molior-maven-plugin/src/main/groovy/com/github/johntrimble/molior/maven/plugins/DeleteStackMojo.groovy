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
import com.amazonaws.services.cloudformation.model.Parameter
import com.amazonaws.services.cloudformation.model.Stack
import com.amazonaws.services.cloudformation.model.StackStatus
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.AssociateAddressRequest
import com.amazonaws.services.ec2.model.DisassociateAddressRequest

/**
*
* @author John Trimble <john.trimble@meltmedia.com>
*
* @aggregator
* @goal deleteStack
*/
class DeleteStackMojo extends AbstractMojo {

  /**
   * @parameter default-value="false"
   */
  boolean skipMostRecent
  
  /**
   * @parameter
   * @required
   */
  String selector
 
  void execute() {
    // Replace any variables in the filter expression that may have been created by previous molior goals. 
    selector = replaceVariables(selector, project.properties)
    
    log.info "Deleting stacks matching filter: ${selector}"
    Filter f = new Filter(selector)
    preProcessStacks(cloudFormation.describeStacks().stacks.grep { 
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
    if( skipMostRecent && stacks.size() )
      stacks.pop()
    return stacks
  }
  
  private String replaceVariables(String text, Map variableValueMap) {
    def matcher = (text =~ /[$][{]([^}]+)[}]/)
    def mrl = []
    while( matcher.find() ) {
      mrl << matcher.toMatchResult()
    }
    def result = text
    mrl.reverse()
      .each { 
        if( !(it.group(1) in variableValueMap) ) 
          throw new IllegalStateException("Could not find variable replacement for '${it.group(1)}'.") }
      .each { result = result[0..it.start()-1] + variableValueMap[it.group(1)] + result[it.end()..result.size()-1] } 
    return result
  }
  
  private String getAgeString(Stack s) {
    PeriodFormat.wordBased().print(
      new Interval(s.creationTime.getTime(), java.lang.System.currentTimeMillis())
      .toPeriod(PeriodType.yearDayTime().withMillisRemoved().withMinutesRemoved().withSecondsRemoved()))
  }
}

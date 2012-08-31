package aws.deploy

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class AwsTask extends DefaultTask {
   def actOnServers(action) {
      def loadBalancer = loadBalancers[deployLevel]
      def serverIds = loadServerIds(loadBalancer)
      def hostMap = loadHostMap(serverIds)
      for(id in serverIds)
         action.call(loadBalancer, hostMap[id], id)
   }
   
   def loadHostMap(serverIds) {
      def client = new com.amazonaws.services.ec2.AmazonEC2Client(awsCredentials)
      def request = new com.amazonaws.services.ec2.model.DescribeInstancesRequest().withInstanceIds(serverIds)
      def result = client.describeInstances(request)
      return result.reservations*.instances.flatten().collectEntries{[it.instanceId,it.publicDnsName]}
   }
   
   def loadServerIds(loadBalancer) {
      def client = new com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient(awsCredentials)
      def request = new com.amazonaws.services.elasticloadbalancing.model.DescribeInstanceHealthRequest()
      request.setLoadBalancerName(loadBalancer)
      def result = client.describeInstanceHealth(request)
      def ids = []
      result.instanceStates.each {
         if (it.state == 'InService')
            ids.add(it.instanceId)
         else
            ids.add(0, it.instanceId)
      }
      return ids
   }
   
   def addServer() {
      def serverId = System.properties['serverId']
      def hostMap = loadHostMap([serverId])
      deployToServer(loadBalancers[deployLevel], hostMap[serverId], serverId, true)
   }
   
   def deployToServer(loadBalancer, serverHost, serverId, newServer = false) {
      new Server(awsCredentials, deployUser, loadBalancer, serverHost, serverId).deploy('build/libs/' + project.name + '-' + version + '.war', newServer)
   }
   
   def getLogs (){
      actOnServers(this.&getLog)
   }
   
   def getLog(loadBalancer, serverHost, serverId) {
      new Server(awsCredentials, deployUser, loadBalancer, serverHost, serverId).copyLogFile('logs/' + deployLevel)
   }
}

package aws.deploy

import org.gradle.api.Project
import ch.ethz.ssh2.*
import com.amazonaws.auth.*
import org.gradle.api.GradleException
import com.amazonaws.services.elasticloadbalancing.*
import com.amazonaws.services.elasticloadbalancing.model.*

class Server {
   static final String delim = "^^^^^command complete^^^^^"
   AwsTomcatDeployPluginExtension options
   Connection connection
   String host, id
   AmazonElasticLoadBalancingClient elbClient
   
   def restart() {
      connect()
      try {
         restartTomcat()
      } finally {
         disconnect()
      }
   }

   private getClient() {
      if(!elbClient)
         elbClient = new AmazonElasticLoadBalancingClient(options.awsCredentials)

      return elbClient
   }

   def deploy(newServer) {
      if(!newServer)
         removeFromLoadBalancer()
      connect()
      try {
         stopTomcat()
         deleteOldApp()
         copyNewApp()
         startTomcat()
      } catch (e) {
         stopTomcat()
         if(!newServer)
            addToLoadBalancer()
         throw e
      } finally {
         disconnect()
      }
      addToLoadBalancer()
      waitForInstanceRegistration()
      println 'Finished ' + id + '\n'
   }
   
   private stopTomcat() {
      doSudo('service ' + options.tomcatServiceName + ' stop', 'Stopping server')
      def pid = cmd('cat ' + options.pidPath)
      if(cmd("ps -A | grep '^ ${pid} '").contains('java'))
         throw new RuntimeException('Failed to stop Tomcat')
   }
   
   private restartTomcat() {
	  removeFromLoadBalancer()
	  
	  try {
		stopTomcat()
		startTomcat()
	  } catch (e) {
		stopTomcat()
		throw e
	  } finally {
		addToLoadBalancer()
	  }
	  
	  waitForInstanceRegistration()
	  println 'Finished ' + id + '\n'
   }
   
   private deleteOldApp() {
      def appDir = options.tomcatPath + '/webapps/' + options.appContext
      doSudo('rm -fr "' + appDir + '" "' + appDir + '.war"', 'Deleting old app')
      if(cmd('ls ' + options.tomcatPath + '/webapps').contains(options.appContext))
         throw new RuntimeException('Failed to delete old app')
      if(options.deleteOldLogs) {
         doSudo('rm -f ' + options.tomcatPath + '/logs/*', 'Deleting old logs')
         if(cmd('ls ' + options.tomcatPath + '/logs').contains('catalina'))
            throw new RuntimeException('Failed to delete logs')
      }
   }

   private copyNewApp() {
      if(options.useS3)
         doSudo('wget -qO ' + options.tomcatPath + '/webapps/' + options.appContext + '.war "' + options.warPath + '"', 'Downloading new app')
      else {
         println 'Copying new app'
         connection.createSCPClient().put(options.warPath, options.appContext + '.war', options.tomcatPath + '/webapps', '0644')
      }
   }
   
   private startTomcat() {
      doSudo('service ' + options.tomcatServiceName + ' start', 'Starting server')
      watchLog(options.maxServerStartWaitTime)
      if(options.pingServer)
         pingServer()
   }
   
   private watchLog(maxWaitTime) {
      def log = ""
      def count = 0
      while(count++ < (maxWaitTime/2)) {
         log = cmd('cat ' + options.tomcatPath + '/logs/catalina.out')
         if(log.contains('Exception'))
            break;
         else if(log.contains('INFO: Server startup in '))
            return
         sleep(2000)
      }
      println log
      throw new GradleException('Failed to start server')
   }
   
   private printIfNotNull(message) {
      if(message)
         println message
   }
   
   private pingServer() {
      new URL(options.pingProtocol + "://" + host + options.pingPath).getText()
      watchLog(30)
   }
   
   private doSudo(command, message) {
      printIfNotNull(message)
      print sudo(command)
   }
   
   private cmd(command) {
      def session = connection.openSession()
      session.execCommand(command + '; echo "' + delim + '"')

      def br = new BufferedReader(new InputStreamReader(new StreamGobbler(session.getStdout())))
      def response = new StringBuilder()
      def line = ""
      while ((line = br.readLine()) != null) {
         if(line.trim().equals(delim))
            break;
         else
            response.append(line).append('\n')
      }
      session.close()
      return response.toString()
   }
   
   private sudo(command) {
      return cmd("sudo " + command)
   }
   
   def copyLogFile(destDir) {
      destDir.mkdirs()
      connect()
      try {
         connection.createSCPClient().get(options.tomcatPath + '/logs/catalina.out', new FileOutputStream(new File(destDir, id + '.log')))
      } finally {
         disconnect()
      }
   }

   private connect() {
      println 'Connecting to ' + id + ' at ' + host
      connection = new Connection(host)
      connection.connect()
      boolean isAuthenticated = connection.authenticateWithPublicKey(options.deployUser, new File(options.sshKeyPath), '')

      if (isAuthenticated == false)
         throw new IOException("Authentication failed.")
   }

   private disconnect() {
      if(connection)
         connection.close()
   }
   
   private removeFromLoadBalancer() {
      if(!options.loadBalancer)
         return
      println 'Removing ' + id + ' from load balancer'
      def request = new DeregisterInstancesFromLoadBalancerRequest()
      def response = client.deregisterInstancesFromLoadBalancer(populatLoadBalancerRequest(request))
      for(instance in response.instances)
         if(instance.instanceId == id)
            throw new RuntimeException("Faild to remove " + id + " from load balancer " + options.loadBalancer)
   }
   
   private populatLoadBalancerRequest(request) {
      request.setLoadBalancerName(options.loadBalancer)
      request.setInstances([new Instance(id)])
      return request
   }
   
   private addToLoadBalancer() {
      if(!options.loadBalancer)
         return
      println 'Adding ' + id + ' to load balancer'
      def request = new RegisterInstancesWithLoadBalancerRequest()
      def response = client.registerInstancesWithLoadBalancer(populatLoadBalancerRequest(request))
      for(instance in response.instances)
         if(instance.instanceId == id) 
            return
      throw new RuntimeException("Faild to add " + id + " to load balancer " + options.loadBalancer)
   }
   
   private waitForInstanceRegistration() {
      if(!options.loadBalancer)
         return
      println 'Waiting for instance registration'
      for(i in 1..60)
         if(getInstanceState(id) == 'InService')
            return
         else
            sleep(2000)
      throw new RuntimeException("Instance never became healthy")
   }
   
   private getInstanceState(id) {
      def request = new DescribeInstanceHealthRequest()
      request.setLoadBalancerName(options.loadBalancer)
      request.setInstances([new Instance(id)])
      def result = client.describeInstanceHealth(request)
      return result.instanceStates*.state[0]
   }
}

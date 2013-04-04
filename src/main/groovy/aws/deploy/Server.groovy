package aws.deploy

import org.gradle.api.Project
import ch.ethz.ssh2.*
import com.amazonaws.auth.*
import org.gradle.api.GradleException
import com.amazonaws.services.elasticloadbalancing.*
import com.amazonaws.services.elasticloadbalancing.model.*

class Server {
   static final String delim = "^^^^^command complete^^^^^"
   Connection connection
   AWSCredentials credentials
   String loadBalancer, host, id
   AwsTomcatDeployPluginExtension options
   Project project
   
   public Server(credentials, loadBalancer, host, id, project, options) {
      this.credentials = credentials
      this.loadBalancer = loadBalancer
      this.host = host
      this.id = id
      this.options = options
      this.project = project
   }

   def deploy(warFile, newServer) {
      if(!newServer)
         removeFromLoadBalancer()
      connect()
      try {
         stopTomcat()
         deleteOldApp()
         copyNewApp(warFile)
         startTomcat()
      } catch (e) {
         stopTomcat()
         addToLoadBalancer()
         throw e
      } finally {
         disconnect()
      }
      addToLoadBalancer()
      waitForInstanceRegistration()
      println 'Finished ' + id + '\n'
   }
   
   def stopTomcat() {
      doSudo('service ' + options.tomcatServiceName + ' stop', 'Stopping server')
      if(cmd('ps -A | grep java').contains('java'))
         throw new RuntimeException('Failed to stop Tomcat')
   }
   
   def deleteOldApp() {
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

   def copyNewApp(warFile) {
      if(options.useS3)
         doSudo('wget -qO ' + options.tomcatPath + '/webapps/' + options.appContext + '.war "' + warFile + '"', 'Downloading new app')
      else {
         println 'Copying new app'
         connection.createSCPClient().put(project.file(warFile), options.appContext + '.war', options.tomcatPath + '/webapps', '0644')
      }
   }
   
   def startTomcat() {
      doSudo('service ' + options.tomcatServiceName + ' start', 'Starting server')
      watchLog()
      if(options.pingServer)
         pingServer()
   }
   
   def watchLog() {
      def log = ""
      for(i in 1..40) {
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
   
   def printIfNotNull(message) {
      if(message)
         println message
   }
   
   def pingServer() {
      new URL(options.pingProtocol + "://" + host + options.pingPath).getText()
      watchLog()
   }
   
   def doSudo(command, message) {
      printIfNotNull(message)
      print sudo(command)
   }
   
   def cmd(command) {
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
   
   def sudo(command) {
      return cmd("sudo " + command)
   }
   
   def copyLogFile(destDir) {
      new File(destDir).mkdirs()
      connect()
      try {
         connection.createSCPClient().get(options.tomcatPath + '/logs/catalina.out', new FileOutputStream(destDir + '/' + id + '.log'))
      } finally {
         disconnect()
      }
   }

   def connect() {
      println 'Connecting to ' + host
      connection = new Connection(host)
      connection.connect()
      boolean isAuthenticated = connection.authenticateWithPublicKey(options.deployUser, new File(options.sshKeyPath), '')

      if (isAuthenticated == false)
         throw new IOException("Authentication failed.")
   }

   def disconnect() {
      if(connection)
         connection.close()
   }
   
   def removeFromLoadBalancer() {
      println 'Removing ' + id + ' from load balancer'
      def client = new AmazonElasticLoadBalancingClient(credentials)
      def request = new DeregisterInstancesFromLoadBalancerRequest()
      def response = client.deregisterInstancesFromLoadBalancer(populatLoadBalancerRequest(request))
      for(instance in response.instances)
         if(instance.instanceId == id)
            throw new RuntimeException("Faild to remove " + id + " from load balancer " + loadBalancer)
   }
   
   def populatLoadBalancerRequest(request) {
      request.setLoadBalancerName(loadBalancer)
      request.setInstances([new Instance(id)])
      return request
   }
   
   def addToLoadBalancer() {
      println 'Adding ' + id + ' to load balancer'
      def client = new AmazonElasticLoadBalancingClient(credentials)
      def request = new RegisterInstancesWithLoadBalancerRequest()
      def response = client.registerInstancesWithLoadBalancer(populatLoadBalancerRequest(request))
      for(instance in response.instances)
         if(instance.instanceId == id) 
            return
      throw new RuntimeException("Faild to add " + id + " to load balancer " + loadBalancer)
   }
   
   def waitForInstanceRegistration() {
      println 'Waiting for instance registration'
      for(i in 1..60)
         if(getInstanceState(id) == 'InService')
            return
         else
            sleep(2000)
      throw new RuntimeException("Instance never became healthy")
   }
   
   def getInstanceState(id) {
      def client = new AmazonElasticLoadBalancingClient(credentials)
      def request = new DescribeInstanceHealthRequest()
      request.setLoadBalancerName(loadBalancer)
      request.setInstances([new Instance(id)])
      def result = client.describeInstanceHealth(request)
      return result.instanceStates*.state[0]
   }
}

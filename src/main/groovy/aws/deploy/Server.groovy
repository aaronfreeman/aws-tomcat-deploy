package aws.deploy

import ch.ethz.ssh2.*
import com.amazonaws.auth.*
import org.gradle.api.GradleException

class Server {
   static final String delim = "^^^^^command complete^^^^^"
   Connection connection
   AWSCredentials credentials
   String loadBalancer, host, id, user
   
   public Server(credentials, user, loadBalancer, host, id) {
      this.credentials = credentials
      this.loadBalancer = loadBalancer
      this.host = host
      this.id = id
      this.user = user
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
      println 'finished ' + id + '\n'
   }
   
   def stopTomcat() {
      doSudo('service tomcat stop', 'stopping server')
      if(cmd('ps -A | grep java').contains('java'))
         throw new RuntimeException('Failed to stop Tomcat')
   }
   
   def deleteOldApp() {
      doSudo('rm -fr /opt/tomcat/webapps/** /opt/tomcat/logs/*', 'deleting old app')
      if(cmd('ls /opt/tomcat/webapps').contains('ROOT'))
         throw new RuntimeException('Failed to delete old app')
      if(cmd('ls /opt/tomcat/logs').contains('catalina'))
         throw new RuntimeException('Failed to delete logs')
   }
   
   def copyNewApp(warFile) {
      copy(warFile, '/opt/tomcat/webapps', 'ROOT.war', 'copying new app')
   }
   
   def startTomcat() {
      doSudo('service tomcat start', 'starting server')
      watchLog()
      pingServer()
      watchLog()
   }
   
   def watchLog() {
      def log = ""
      for(i in 1..40) {
         log = cmd('cat /opt/tomcat/logs/catalina.out')
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
      new URL("http://" + host + "/fiji/login").getText()
   }
   
   def doSudo(command, message) {
      printIfNotNull(message)
      print sudo(command)
   }
   
   def cmd(command) {
      def session = connection.openSession()
      session.execCommand(command + '; echo "' + delim + '"')

      def br = new BufferedReader(new InputStreamReader(new ch.ethz.ssh2.StreamGobbler(session.getStdout())))
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
   
   def copy(localFile, destDir, destFileName, message) {
      printIfNotNull(message)
      connection.createSCPClient().put(localFile, destFileName, destDir, '0644')
   }
   
   def copyLogFile(destDir) {
      new File(destDir).mkdirs()
      connect()
      try {
         connection.createSCPClient().get('/opt/tomcat/logs/catalina.out', new FileOutputStream(destDir + '/' + id + '.log'))
      } finally {
         disconnect()
      }
   }

   def connect() {
      println 'connecting to ' + host
      connection = new ch.ethz.ssh2.Connection(host)
      connection.connect()
      boolean isAuthenticated = connection.authenticateWithPublicKey(user, new File(System.getProperty('user.home') + '/.ssh/id_rsa'), '')

      if (isAuthenticated == false)
         throw new IOException("Authentication failed.")
   }

   def disconnect() {
      if(connection)
         connection.close()
   }
   
   def removeFromLoadBalancer() {
      println 'removing ' + id + ' from load balancer'
      def client = new com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient(credentials)
      def request = new com.amazonaws.services.elasticloadbalancing.model.DeregisterInstancesFromLoadBalancerRequest()
      def response = client.deregisterInstancesFromLoadBalancer(populatLoadBalancerRequest(request))
      for(instance in response.instances)
         if(instance.instanceId == id)
            throw new RuntimeException("Faild to remove " + id + " from load balancer " + loadBalancer)
   }
   
   def populatLoadBalancerRequest(request) {
      request.setLoadBalancerName(loadBalancer)
      request.setInstances([new com.amazonaws.services.elasticloadbalancing.model.Instance(id)])
      return request
   }
   
   def addToLoadBalancer() {
      println 'adding ' + id + ' to load balancer'
      def client = new com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient(credentials)
      def request = new com.amazonaws.services.elasticloadbalancing.model.RegisterInstancesWithLoadBalancerRequest()
      def response = client.registerInstancesWithLoadBalancer(populatLoadBalancerRequest(request))
      for(instance in response.instances)
         if(instance.instanceId == id) 
            return
      throw new RuntimeException("Faild to add " + id + " to load balancer " + loadBalancer)
   }
   
   def waitForInstanceRegistration() {
      println 'waiting for instance registration'
      for(i in 1..60)
         if(getInstanceState(id) == 'InService')
            return
         else
            sleep(2000)
      throw new RuntimeException("Instance never became healthy")
   }
   
   def getInstanceState(id) {
      def client = new com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient(credentials)
      def request = new com.amazonaws.services.elasticloadbalancing.model.DescribeInstanceHealthRequest()
      request.setLoadBalancerName(loadBalancer)
      request.setInstances([new com.amazonaws.services.elasticloadbalancing.model.Instance(id)])
      def result = client.describeInstanceHealth(request)
      return result.instanceStates*.state[0]
   }
}

aws-tomcat-deploy
=================

A gradle script for deploying Java and Grails applications to Tomcat on AWS Linux. It can interact AWS ELB to update several servers, and connects to each server
user SSH to stop the application, update it, then start it back up.

Three tasks are defined:
	deploy - this will deploy the application to the server
	addServer - this will deploy the application to a new server, and add it to a load balancer
	getLogs - this will pull the catalina.out file from each server and copy them to your local machine

To use this, put this in your build.gradle file:

buildscript {
   repositories {
      mavenCentral()
      ivy {
        url "https://raw.github.com/aaronfreeman/repo/master"
        layout "pattern", {
            artifact "[module]/[revision]/[artifact].[ext]"
        }
      }
   }

   dependencies { 
      classpath 'aws-tomcat-deploy:aws-tomcat-deploy:1.0', 
                'com.amazonaws:aws-java-sdk:1.3.20', 
                'ch.ethz.ganymed:ganymed-ssh2:build210'
    }
}

apply plugin: 'aws.tomcat.deploy'

// These are the configuration options, the defaul values are shown

awsTomcatDeploy {
	accessKey = '' 					// These are your Amazon keys, they give the script access to your server list and load balancers
	secretKey = ''
	
	loadBalancer = null  			// This allows you to deploy to all servers attached to the load balancer, us this or serverId
	serverId = null 				// Set this if you only want to deploy to a single server, or if you are calling addServer
	
	deployUser = 'ec2-user' 		// The user that shuould be used on the SSH connection to the server
	sshKeyPath = '~/.ssh/id_rsa'	// The location of the private key for the SSH connection user
	
	tomcatPath = '/opt/tomcat'		// The locaiton of the installation director of Tomcat on the server
	tomcatServiceName = 'tomcat'	// The name of ther tomcat service on the server
	
	warPath = null					// The local path to the war file you are deploying
	appContext = 'ROOT'				// The context root you want the app to have on the server
	
	deleteOldLogs = true			// If true the script will delete old server logs when it deploys

	useS3 = false					// If true the script will upload the war to an S3 bucket and pull it down from there on each server
									// this is useful if you deploy from a machine with slow internet so you don't have to upload the war
									// to each server
	s3bucket = ''					// The name of the S3 bucket to use, the script will delete the war from the bucket once the deploy is done
	
	pingServer = true				// True if you want the script to ping the server after start up to make sure it is running
	pingProtocol = 'http'			// The protocol to use on the ping
	pingPath = '/'					// The url path to ping
	
	localLogsDir = null				// The directory to use to put the logs in when getLogs is called
}

/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2016 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.cluster;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpState;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.lang.StringUtils;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.changed.ChangedFlag;
import org.pentaho.di.core.encryption.Encr;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleValueException;
import org.pentaho.di.core.logging.LogChannel;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaString;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.core.variables.Variables;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.core.xml.XMLInterface;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.repository.ObjectId;
import org.pentaho.di.repository.ObjectRevision;
import org.pentaho.di.repository.RepositoryDirectory;
import org.pentaho.di.repository.RepositoryDirectoryInterface;
import org.pentaho.di.repository.RepositoryElementInterface;
import org.pentaho.di.repository.RepositoryObjectType;
import org.pentaho.di.shared.SharedObjectInterface;
import org.pentaho.di.www.AddExportServlet;
import org.pentaho.di.www.AllocateServerSocketServlet;
import org.pentaho.di.www.CleanupTransServlet;
import org.pentaho.di.www.GetJobStatusServlet;
import org.pentaho.di.www.GetPropertiesServlet;
import org.pentaho.di.www.GetSlavesServlet;
import org.pentaho.di.www.GetStatusServlet;
import org.pentaho.di.www.GetTransStatusServlet;
import org.pentaho.di.www.NextSequenceValueServlet;
import org.pentaho.di.www.PauseTransServlet;
import org.pentaho.di.www.RegisterPackageServlet;
import org.pentaho.di.www.RemoveJobServlet;
import org.pentaho.di.www.RemoveTransServlet;
import org.pentaho.di.www.SlaveServerDetection;
import org.pentaho.di.www.SlaveServerJobStatus;
import org.pentaho.di.www.SlaveServerStatus;
import org.pentaho.di.www.SlaveServerTransStatus;
import org.pentaho.di.www.SniffStepServlet;
import org.pentaho.di.www.SslConfiguration;
import org.pentaho.di.www.StartJobServlet;
import org.pentaho.di.www.StartTransServlet;
import org.pentaho.di.www.StopJobServlet;
import org.pentaho.di.www.StopTransServlet;
import org.pentaho.di.www.WebResult;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

public class SlaveServer extends ChangedFlag implements Cloneable, SharedObjectInterface, VariableSpace,
  RepositoryElementInterface, XMLInterface {
  private static Class<?> PKG = SlaveServer.class; // for i18n purposes, needed by Translator2!!

  public static final String STRING_SLAVESERVER = "Slave Server";

  private static final Random RANDOM = new Random();

  public static final String XML_TAG = "slaveserver";

  public static final RepositoryObjectType REPOSITORY_ELEMENT_TYPE = RepositoryObjectType.SLAVE_SERVER;

  private static final String HTTP = "http";
  private static final String HTTPS = "https";

  public static final String SSL_MODE_TAG = "sslMode";

  private static final int NOT_FOUND_ERROR = 404;

  public static final int KETTLE_CARTE_RETRIES = getNumberOfSlaveServerRetries();

  public static final int KETTLE_CARTE_RETRY_BACKOFF_INCREMENTS = getBackoffIncrements();

  private static int getNumberOfSlaveServerRetries() {
    try {
      return Integer.parseInt( Const.NVL( System.getProperty( "KETTLE_CARTE_RETRIES" ), "0" ) );
    } catch ( Exception e ) {
      return 0;
    }
  }

  public static int getBackoffIncrements() {
    try {
      return Integer.parseInt( Const.NVL( System.getProperty( "KETTLE_CARTE_RETRY_BACKOFF_INCREMENTS" ), "1000" ) );
    } catch ( Exception e ) {
      return 1000;
    }
  }

  private LogChannelInterface log;

  private String name;

  private String hostname;

  private String port;

  private String webAppName;

  private String username;

  private String password;

  private String proxyHostname;

  private String proxyPort;

  private String nonProxyHosts;

  private String propertiesMasterName;

  private boolean overrideExistingProperties;

  private boolean master;

  private boolean shared;

  private ObjectId id;

  private VariableSpace variables = new Variables();

  private ObjectRevision objectRevision;

  private Date changedDate;

  private boolean sslMode;

  private SslConfiguration sslConfig;

  private ReadWriteLock lock;

  public SlaveServer() {
    initializeVariablesFrom( null );
    id = null;
    this.log = new LogChannel( STRING_SLAVESERVER );
    this.changedDate = new Date();
    lock = new ReentrantReadWriteLock();
  }

  public SlaveServer( String name, String hostname, String port, String username, String password ) {
    this( name, hostname, port, username, password, null, null, null, false, false );
  }

  public SlaveServer( String name, String hostname, String port, String username, String password,
                      String proxyHostname, String proxyPort, String nonProxyHosts, boolean master ) {
    this( name, hostname, port, username, password, proxyHostname, proxyPort, nonProxyHosts, master, false );
  }

  public SlaveServer( String name, String hostname, String port, String username, String password,
                      String proxyHostname, String proxyPort, String nonProxyHosts, boolean master, boolean ssl ) {
    this();
    this.name = name;
    this.hostname = hostname;
    this.port = port;
    this.username = username;
    this.password = password;

    this.proxyHostname = proxyHostname;
    this.proxyPort = proxyPort;
    this.nonProxyHosts = nonProxyHosts;

    this.master = master;
    initializeVariablesFrom( null );
    this.log = new LogChannel( this );
  }

  public SlaveServer( Node slaveNode ) {
    this();
    this.name = XMLHandler.getTagValue( slaveNode, "name" );
    this.hostname = XMLHandler.getTagValue( slaveNode, "hostname" );
    this.port = XMLHandler.getTagValue( slaveNode, "port" );
    this.webAppName = XMLHandler.getTagValue( slaveNode, "webAppName" );
    this.username = XMLHandler.getTagValue( slaveNode, "username" );
    this.password = Encr.decryptPasswordOptionallyEncrypted( XMLHandler.getTagValue( slaveNode, "password" ) );
    this.proxyHostname = XMLHandler.getTagValue( slaveNode, "proxy_hostname" );
    this.proxyPort = XMLHandler.getTagValue( slaveNode, "proxy_port" );
    this.nonProxyHosts = XMLHandler.getTagValue( slaveNode, "non_proxy_hosts" );
    this.propertiesMasterName = XMLHandler.getTagValue( slaveNode, "get_properties_from_master" );
    this.overrideExistingProperties =
      "Y".equalsIgnoreCase( XMLHandler.getTagValue( slaveNode, "override_existing_properties" ) );
    this.master = "Y".equalsIgnoreCase( XMLHandler.getTagValue( slaveNode, "master" ) );
    initializeVariablesFrom( null );
    this.log = new LogChannel( this );

    setSslMode( "Y".equalsIgnoreCase( XMLHandler.getTagValue( slaveNode, SSL_MODE_TAG ) ) );
    Node sslConfig = XMLHandler.getSubNode( slaveNode, SslConfiguration.XML_TAG );
    if ( sslConfig != null ) {
      setSslMode( true );
      this.sslConfig = new SslConfiguration( sslConfig );
    }
  }

  public LogChannelInterface getLogChannel() {
    return log;
  }

  public String getXML() {
    StringBuilder xml = new StringBuilder();

    xml.append( "<" ).append( XML_TAG ).append( ">" );

    lock.readLock().lock();
    try {
      xml.append( XMLHandler.addTagValue( "name", name, false ) );
      xml.append( XMLHandler.addTagValue( "hostname", hostname, false ) );
      xml.append( XMLHandler.addTagValue( "port", port, false ) );
      xml.append( XMLHandler.addTagValue( "webAppName", webAppName, false ) );
      xml.append( XMLHandler.addTagValue( "username", username, false ) );
      xml.append( XMLHandler.addTagValue( "password", Encr.encryptPasswordIfNotUsingVariables( password ), false ) );
      xml.append( XMLHandler.addTagValue( "proxy_hostname", proxyHostname, false ) );
      xml.append( XMLHandler.addTagValue( "proxy_port", proxyPort, false ) );
      xml.append( XMLHandler.addTagValue( "non_proxy_hosts", nonProxyHosts, false ) );
      xml.append( XMLHandler.addTagValue( "master", master, false ) );
      xml.append( XMLHandler.addTagValue( SSL_MODE_TAG, isSslMode(), false ) );
      if ( sslConfig != null ) {
        xml.append( sslConfig.getXML() );
      }
    } finally {
      lock.readLock().unlock();
    }

    xml.append( "</" ).append( XML_TAG ).append( ">" );

    return xml.toString();
  }

  public Object clone() {
    SlaveServer slaveServer = new SlaveServer();
    lock.readLock().lock();
    try {
      slaveServer.replaceMeta( this );
    } finally {
      lock.readLock().unlock();
    }
    return slaveServer;
  }

  public void replaceMeta( SlaveServer slaveServer ) {
    lock.writeLock().lock();
    try {
      this.name = slaveServer.name;
      this.hostname = slaveServer.hostname;
      this.port = slaveServer.port;
      this.webAppName = slaveServer.webAppName;
      this.username = slaveServer.username;
      this.password = slaveServer.password;
      this.proxyHostname = slaveServer.proxyHostname;
      this.proxyPort = slaveServer.proxyPort;
      this.nonProxyHosts = slaveServer.nonProxyHosts;
      this.master = slaveServer.master;

      this.id = slaveServer.id;
      this.shared = slaveServer.shared;
      this.sslMode = slaveServer.sslMode;

      this.setChanged( true );
    } finally {
      lock.writeLock().unlock();
    }
  }

  public String toString() {
    lock.readLock().lock();
    try {
      return name;
    } finally {
      lock.readLock().unlock();
    }
  }

  public String getServerAndPort() {
    String realHostname;

    lock.readLock().lock();
    try {
      realHostname = environmentSubstitute( hostname );
    } finally {
      lock.readLock().unlock();
    }

    if ( !Const.isEmpty( realHostname ) ) {
      return realHostname + getPortSpecification();
    }
    return "Slave Server";
  }

  public boolean equals( Object obj ) {
    if ( !( obj instanceof SlaveServer ) ) {
      return false;
    }
    SlaveServer slave = (SlaveServer) obj;
    lock.readLock().lock();
    try {
      return name.equalsIgnoreCase( slave.getName() );
    } finally {
      lock.readLock().unlock();
    }
  }

  public int hashCode() {
    return name.hashCode();
  }

  public String getHostname() {
    lock.readLock().lock();
    try {
      return hostname;
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setHostname( String urlString ) {
    lock.writeLock().lock();
    this.hostname = urlString;
    lock.writeLock().unlock();
  }

  /**
   * @return the password
   */
  public String getPassword() {
    lock.readLock().lock();
    try {
      return password;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @param password the password to set
   */
  public void setPassword( String password ) {
    lock.writeLock().lock();
    this.password = password;
    lock.writeLock().unlock();
  }

  /**
   * @return the username
   */
  public String getUsername() {
    lock.readLock().lock();
    try {
      return username;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @param username the username to set
   */
  public void setUsername( String username ) {
    lock.writeLock().lock();
    this.username = username;
    lock.writeLock().unlock();
  }

  /**
   * @return the username
   */
  public String getWebAppName() {
    lock.readLock().lock();
    try {
      return webAppName;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @param webAppName the web application name to set
   */
  public void setWebAppName( String webAppName ) {
    lock.writeLock().lock();
    this.webAppName = webAppName;
    lock.writeLock().unlock();
  }

  /**
   * @return the nonProxyHosts
   */
  public String getNonProxyHosts() {
    lock.readLock().lock();
    try {
      return nonProxyHosts;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @param nonProxyHosts the nonProxyHosts to set
   */
  public void setNonProxyHosts( String nonProxyHosts ) {
    lock.writeLock().lock();
    this.nonProxyHosts = nonProxyHosts;
    lock.writeLock().unlock();
  }

  /**
   * @return the proxyHostname
   */
  public String getProxyHostname() {
    lock.readLock().lock();
    try {
      return proxyHostname;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @param proxyHostname the proxyHostname to set
   */
  public void setProxyHostname( String proxyHostname ) {
    lock.writeLock().lock();
    this.proxyHostname = proxyHostname;
    lock.writeLock().unlock();
  }

  /**
   * @return the proxyPort
   */
  public String getProxyPort() {
    lock.readLock().lock();
    try {
      return proxyPort;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @param proxyPort the proxyPort to set
   */
  public void setProxyPort( String proxyPort ) {
    lock.writeLock().lock();
    this.proxyPort = proxyPort;
    lock.writeLock().unlock();
  }

  /**
   * @return the Master name for read properties
   */
  public String getPropertiesMasterName() {
    lock.readLock().lock();
    try {
      return propertiesMasterName;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @return flag for read properties from Master
   */
  public boolean isOverrideExistingProperties() {
    lock.readLock().lock();
    try {
      return overrideExistingProperties;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @return the port
   */
  public String getPort() {
    lock.readLock().lock();
    try {
      return port;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @param port the port to set
   */
  public void setPort( String port ) {
    lock.writeLock().lock();
    this.port = port;
    lock.writeLock().unlock();
  }

  public String getPortSpecification() {
    lock.readLock().lock();
    try {
      String realPort = environmentSubstitute( port );
      String portSpec = ":" + realPort;
      if ( Const.isEmpty( realPort ) || port.equals( "80" ) ) {
        portSpec = "";
      }
      return portSpec;
    } finally {
      lock.readLock().unlock();
    }

  }

  public String constructUrl( String serviceAndArguments ) throws UnsupportedEncodingException {
    lock.readLock().lock();
    try {
      String realHostname = environmentSubstitute( hostname );
      if ( !StringUtils.isBlank( webAppName ) ) {
        serviceAndArguments = "/" + environmentSubstitute( getWebAppName() ) + serviceAndArguments;
      }

      String result =
        ( isSslMode() ? HTTPS : HTTP ) + "://" + realHostname + getPortSpecification() + serviceAndArguments;
      result = Const.replace( result, " ", "%20" );
      return result;

    } finally {
      lock.readLock().unlock();
    }

  }

  // Method is defined as package-protected in order to be accessible by unit tests
  PostMethod buildSendXMLMethod( byte[] content, String service ) throws Exception {
    // Prepare HTTP put
    //
    String urlString = constructUrl( service );
    if ( log.isDebug() ) {
      log.logDebug( BaseMessages.getString( PKG, "SlaveServer.DEBUG_ConnectingTo", urlString ) );
    }
    PostMethod postMethod = new PostMethod( urlString );

    // Request content will be retrieved directly from the input stream
    //
    RequestEntity entity = new ByteArrayRequestEntity( content );

    postMethod.setRequestEntity( entity );
    postMethod.setDoAuthentication( true );
    postMethod.addRequestHeader( new Header( "Content-Type", "text/xml;charset=" + Const.XML_ENCODING ) );

    return postMethod;
  }

  public String sendXML( String xml, String service ) throws Exception {
    PostMethod method = buildSendXMLMethod( xml.getBytes( Const.XML_ENCODING ), service );

    // Execute request
    //
    try {
      int result = getHttpClient().executeMethod( method );

      // The status code
      if ( log.isDebug() ) {
        log.logDebug( BaseMessages.getString( PKG, "SlaveServer.DEBUG_ResponseStatus", Integer.toString( result ) ) );
      }

      String responseBody = getResponseBodyAsString( method.getResponseBodyAsStream() );

      if ( log.isDebug() ) {
        log.logDebug( BaseMessages.getString( PKG, "SlaveServer.DEBUG_ResponseBody", responseBody ) );
      }

      if ( result >= 400 ) {
        String message;
        if ( result == NOT_FOUND_ERROR ) {
          message = String.format( "%s%s%s%s",
            BaseMessages.getString( PKG, "SlaveServer.Error.404.Title" ),
            Const.CR, Const.CR,
            BaseMessages.getString( PKG, "SlaveServer.Error.404.Message" )
          );
        } else {
          message = String.format( "HTTP Status %d - %s - %s",
            method.getStatusCode(),
            method.getPath(),
            method.getStatusText()
          );
        }
        throw new KettleException( message );
      }

      return responseBody;
    } finally {
      // Release current connection to the connection pool once you are done
      method.releaseConnection();
      if ( log.isDetailed() ) {
        log.logDetailed( BaseMessages.getString( PKG, "SlaveServer.DETAILED_SentXmlToService", service,
          environmentSubstitute( hostname ) ) );
      }
    }
  }

  // Method is defined as package-protected in order to be accessible by unit tests
  PostMethod buildSendExportMethod( String type, String load, InputStream is ) throws UnsupportedEncodingException {
    String serviceUrl = RegisterPackageServlet.CONTEXT_PATH;
    if ( type != null && load != null ) {
      serviceUrl +=
        "/?" + AddExportServlet.PARAMETER_TYPE + "=" + type + "&" + AddExportServlet.PARAMETER_LOAD + "="
          + URLEncoder.encode( load, "UTF-8" );
    }

    String urlString = constructUrl( serviceUrl );
    if ( log.isDebug() ) {
      log.logDebug( BaseMessages.getString( PKG, "SlaveServer.DEBUG_ConnectingTo", urlString ) );
    }

    PostMethod method = new PostMethod( urlString );
    method.setRequestEntity( new InputStreamRequestEntity( is ) );
    method.setDoAuthentication( true );
    method.addRequestHeader( new Header( "Content-Type", "binary/zip" ) );

    return method;
  }

  /**
   * Send an exported archive over to this slave server
   *
   * @param filename The archive to send
   * @param type     The type of file to add to the slave server (AddExportServlet.TYPE_*)
   * @param load     The filename to load in the archive (the .kjb or .ktr)
   * @return the XML of the web result
   * @throws Exception in case something goes awry
   */
  public String sendExport( String filename, String type, String load ) throws Exception {
    // Request content will be retrieved directly from the input stream
    //
    InputStream is = null;
    try {
      is = KettleVFS.getInputStream( KettleVFS.getFileObject( filename ) );

      // Execute request
      //
      PostMethod method = buildSendExportMethod( type, load, is );
      try {
        int result = getHttpClient().executeMethod( method );

        // The status code
        if ( log.isDebug() ) {
          log.logDebug( BaseMessages.getString( PKG, "SlaveServer.DEBUG_ResponseStatus", Integer.toString( result ) ) );
        }

        String responseBody = getResponseBodyAsString( method.getResponseBodyAsStream() );

        // String body = post.getResponseBodyAsString();
        if ( log.isDebug() ) {
          log.logDebug( BaseMessages.getString( PKG, "SlaveServer.DEBUG_ResponseBody", responseBody ) );
        }

        if ( result >= 400 ) {
          throw new KettleException( String.format( "HTTP Status %d - %s - %s", method.getStatusCode(), method
            .getPath(), method.getStatusText() ) );
        }

        return responseBody;
      } finally {
        // Release current connection to the connection pool once you are done
        method.releaseConnection();
        if ( log.isDetailed() ) {
          log.logDetailed( BaseMessages.getString( PKG, "SlaveServer.DETAILED_SentExportToService",
              RegisterPackageServlet.CONTEXT_PATH, environmentSubstitute( hostname ) ) );
        }
      }
    } finally {
      try {
        if ( is != null ) {
          is.close();
        }
      } catch ( IOException ignored ) {
        // nothing to do here...
      }
    }
  }

  public void addProxy( HttpClient client ) {
    String hostName;
    String proxyHost;
    String proxyPort;
    String nonProxyHosts;

    lock.readLock().lock();
    try {
      hostName = environmentSubstitute( this.hostname );
      proxyHost = environmentSubstitute( this.proxyHostname );
      proxyPort = environmentSubstitute( this.proxyPort );
      nonProxyHosts = environmentSubstitute( this.nonProxyHosts );
    } finally {
      lock.readLock().unlock();
    }

    if ( !Const.isEmpty( proxyHost ) && !Const.isEmpty( proxyPort ) ) {
      // skip applying proxy if non-proxy host matches
      if ( !Const.isEmpty( nonProxyHosts ) && !Const.isEmpty( hostName ) && hostName.matches( nonProxyHosts ) ) {
        return;
      }
      client.getHostConfiguration().setProxy( proxyHost, Integer.parseInt( proxyPort ) );
    }
  }

  public void addCredentials( HttpClient client ) {
    HttpState state = client.getState();

    lock.readLock().lock();
    try {
      state.setCredentials(
        new AuthScope( environmentSubstitute( hostname ), Const.toInt( environmentSubstitute( port ), 80 ) ),
        new UsernamePasswordCredentials( environmentSubstitute( username ), Encr
          .decryptPasswordOptionallyEncrypted( environmentSubstitute( password ) ) ) );
    } finally {
      lock.readLock().unlock();
    }

    client.getParams().setAuthenticationPreemptive( true );
  }

  /**
   * @return the master
   */
  public boolean isMaster() {
    lock.readLock().lock();
    try {
      return master;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @param master the master to set
   */
  public void setMaster( boolean master ) {
    lock.writeLock().lock();
    this.master = master;
    lock.writeLock().unlock();

  }

  public String execService( String service, boolean retry ) throws Exception {
    int tries = 0;
    int maxRetries = 0;
    if ( retry ) {
      maxRetries = KETTLE_CARTE_RETRIES;
    }
    while ( true ) {
      try {
        return execService( service );
      } catch ( Exception e ) {
        if ( tries >= maxRetries ) {
          throw e;
        } else {
          try {
            Thread.sleep( getDelay( tries ) );
          } catch ( InterruptedException e2 ) {
            //ignore
          }
        }
      }
      tries++;
    }
  }

  public static long getDelay( int trial ) {
    long current = KETTLE_CARTE_RETRY_BACKOFF_INCREMENTS;
    long previous = 0;
    for ( int i = 0; i < trial; i++ ) {
      long tmp = current;
      current = current + previous;
      previous = tmp;
    }
    return current + RANDOM.nextInt( (int) Math.min( Integer.MAX_VALUE, current / 4L ) );
  }

  public String execService( String service ) throws Exception {
    return execService( service, new HashMap<String, String>() );
  }

  // Method is defined as package-protected in order to be accessible by unit tests
  String getResponseBodyAsString( InputStream is ) throws IOException {
    BufferedReader bufferedReader = new BufferedReader( new InputStreamReader( is ) );
    StringBuilder bodyBuffer = new StringBuilder();
    String line;

    try {
      while ( ( line = bufferedReader.readLine() ) != null ) {
        bodyBuffer.append( line );
      }
    } finally {
      bufferedReader.close();
    }

    return bodyBuffer.toString();
  }

  // Method is defined as package-protected in order to be accessible by unit tests
  GetMethod buildExecuteServiceMethod( String service, Map<String, String> headerValues )
    throws UnsupportedEncodingException {
    GetMethod method = new GetMethod( constructUrl( service ) );

    for ( String key : headerValues.keySet() ) {
      method.setRequestHeader( key, headerValues.get( key ) );
    }
    return method;
  }

  public String execService( String service, Map<String, String> headerValues ) throws Exception {
    // Prepare HTTP get
    //
    GetMethod method = buildExecuteServiceMethod( service, headerValues );

    // Execute request
    //
    try {
      int result = getHttpClient().executeMethod( method );

      // The status code
      if ( log.isDebug() ) {
        log.logDebug( BaseMessages.getString( PKG, "SlaveServer.DEBUG_ResponseStatus", Integer.toString( result ) ) );
      }

      String responseBody = method.getResponseBodyAsString();

      if ( log.isDetailed() ) {
        log.logDetailed( BaseMessages.getString( PKG, "SlaveServer.DETAILED_FinishedReading", Integer
            .toString( responseBody.getBytes().length ) ) );
      }
      if ( log.isDebug() ) {
        log.logDebug( BaseMessages.getString( PKG, "SlaveServer.DEBUG_ResponseBody", responseBody ) );
      }

      if ( result >= 400 ) {
        throw new KettleException( String.format( "HTTP Status %d - %s - %s", method.getStatusCode(), method.getPath(),
          method.getStatusText() ) );
      }

      return responseBody;
    } finally {
      // Release current connection to the connection pool once you are done
      method.releaseConnection();
      if ( log.isDetailed() ) {
        log.logDetailed( BaseMessages.getString( PKG, "SlaveServer.DETAILED_ExecutedService", service, hostname ) );
      }
    }
  }

  // Method is defined as package-protected in order to be accessible by unit tests
  HttpClient getHttpClient() {
    HttpClient client = SlaveConnectionManager.getInstance().createHttpClient();
    addCredentials( client );
    addProxy( client );
    return client;
  }

  public SlaveServerStatus getStatus() throws Exception {
    String xml = execService( GetStatusServlet.CONTEXT_PATH + "/?xml=Y" );
    return SlaveServerStatus.fromXML( xml );
  }

  public List<SlaveServerDetection> getSlaveServerDetections() throws Exception {
    String xml = execService( GetSlavesServlet.CONTEXT_PATH + "/" );
    Document document = XMLHandler.loadXMLString( xml );
    Node detectionsNode = XMLHandler.getSubNode( document, GetSlavesServlet.XML_TAG_SLAVESERVER_DETECTIONS );
    int nrDetections = XMLHandler.countNodes( detectionsNode, SlaveServerDetection.XML_TAG );

    List<SlaveServerDetection> detections = new ArrayList<SlaveServerDetection>();
    for ( int i = 0; i < nrDetections; i++ ) {
      Node detectionNode = XMLHandler.getSubNodeByNr( detectionsNode, SlaveServerDetection.XML_TAG, i );
      SlaveServerDetection detection = new SlaveServerDetection( detectionNode );
      detections.add( detection );
    }
    return detections;
  }

  public SlaveServerTransStatus getTransStatus( String transName, String carteObjectId, int startLogLineNr )
    throws Exception {
    String xml =
        execService( GetTransStatusServlet.CONTEXT_PATH + "/?name=" + URLEncoder.encode( transName, "UTF-8" ) + "&id="
            + Const.NVL( carteObjectId, "" ) + "&xml=Y&from=" + startLogLineNr, true );
    return SlaveServerTransStatus.fromXML( xml );
  }

  public SlaveServerJobStatus getJobStatus( String jobName, String carteObjectId, int startLogLineNr ) throws Exception {
    String xml =
        execService( GetJobStatusServlet.CONTEXT_PATH + "/?name=" + URLEncoder.encode( jobName, "UTF-8" ) + "&id="
            + Const.NVL( carteObjectId, "" ) + "&xml=Y&from=" + startLogLineNr, true );
    return SlaveServerJobStatus.fromXML( xml );
  }

  public WebResult stopTransformation( String transName, String carteObjectId ) throws Exception {
    String xml =
        execService( StopTransServlet.CONTEXT_PATH + "/?name=" + URLEncoder.encode( transName, "UTF-8" ) + "&id="
            + Const.NVL( carteObjectId, "" ) + "&xml=Y" );
    return WebResult.fromXMLString( xml );
  }

  public WebResult pauseResumeTransformation( String transName, String carteObjectId ) throws Exception {
    String xml =
        execService( PauseTransServlet.CONTEXT_PATH + "/?name=" + URLEncoder.encode( transName, "UTF-8" ) + "&id="
            + Const.NVL( carteObjectId, "" ) + "&xml=Y" );
    return WebResult.fromXMLString( xml );
  }

  public WebResult removeTransformation( String transName, String carteObjectId ) throws Exception {
    String xml =
        execService( RemoveTransServlet.CONTEXT_PATH + "/?name=" + URLEncoder.encode( transName, "UTF-8" ) + "&id="
            + Const.NVL( carteObjectId, "" ) + "&xml=Y" );
    return WebResult.fromXMLString( xml );
  }

  public WebResult removeJob( String jobName, String carteObjectId ) throws Exception {
    String xml =
        execService( RemoveJobServlet.CONTEXT_PATH + "/?name=" + URLEncoder.encode( jobName, "UTF-8" ) + "&id="
            + Const.NVL( carteObjectId, "" ) + "&xml=Y" );
    return WebResult.fromXMLString( xml );
  }

  public WebResult stopJob( String transName, String carteObjectId ) throws Exception {
    String xml =
        execService( StopJobServlet.CONTEXT_PATH + "/?name=" + URLEncoder.encode( transName, "UTF-8" ) + "&xml=Y&id="
            + Const.NVL( carteObjectId, "" ) );
    return WebResult.fromXMLString( xml );
  }

  public WebResult startTransformation( String transName, String carteObjectId ) throws Exception {
    String xml =
        execService( StartTransServlet.CONTEXT_PATH + "/?name=" + URLEncoder.encode( transName, "UTF-8" ) + "&id="
            + Const.NVL( carteObjectId, "" ) + "&xml=Y" );
    return WebResult.fromXMLString( xml );
  }

  public WebResult startJob( String jobName, String carteObjectId ) throws Exception {
    String xml =
        execService( StartJobServlet.CONTEXT_PATH + "/?name=" + URLEncoder.encode( jobName, "UTF-8" ) + "&xml=Y&id="
            + Const.NVL( carteObjectId, "" ) );
    return WebResult.fromXMLString( xml );
  }

  public WebResult cleanupTransformation( String transName, String carteObjectId ) throws Exception {
    String xml =
        execService( CleanupTransServlet.CONTEXT_PATH + "/?name=" + URLEncoder.encode( transName, "UTF-8" ) + "&id="
            + Const.NVL( carteObjectId, "" ) + "&xml=Y" );
    return WebResult.fromXMLString( xml );
  }

  public WebResult deAllocateServerSockets( String transName, String clusteredRunId ) throws Exception {
    String xml =
        execService( CleanupTransServlet.CONTEXT_PATH + "/?name=" + URLEncoder.encode( transName, "UTF-8" ) + "&id="
            + Const.NVL( clusteredRunId, "" ) + "&xml=Y&sockets=Y" );
    return WebResult.fromXMLString( xml );
  }

  public Properties getKettleProperties() throws Exception {
    String xml = execService( GetPropertiesServlet.CONTEXT_PATH + "/?xml=Y" );
    String decryptedXml =  Encr.decryptPassword( xml );
    InputStream in = new ByteArrayInputStream( decryptedXml.getBytes() );
    Properties properties = new Properties();
    properties.loadFromXML( in );
    return properties;
  }

  public static SlaveServer findSlaveServer( List<SlaveServer> slaveServers, String name ) {
    for ( SlaveServer slaveServer : slaveServers ) {
      if ( slaveServer.getName() != null && slaveServer.getName().equalsIgnoreCase( name ) ) {
        return slaveServer;
      }
    }
    return null;
  }

  public static SlaveServer findSlaveServer( List<SlaveServer> slaveServers, ObjectId id ) {
    for ( SlaveServer slaveServer : slaveServers ) {
      if ( slaveServer.getObjectId() != null && slaveServer.getObjectId().equals( id ) ) {
        return slaveServer;
      }
    }
    return null;
  }

  public static String[] getSlaveServerNames( List<SlaveServer> slaveServers ) {
    String[] names = new String[ slaveServers.size() ];
    for ( int i = 0; i < slaveServers.size(); i++ ) {
      SlaveServer slaveServer = slaveServers.get( i );
      names[ i ] = slaveServer.getName();
    }
    return names;
  }

  public int allocateServerSocket( String runId, int portRangeStart, String hostname,
                                   String transformationName, String sourceSlaveName,
                                   String sourceStepName, String sourceStepCopy,
                                   String targetSlaveName, String targetStepName, String targetStepCopy )
    throws Exception {

    // Look up the IP address of the given hostname
    // Only this way we'll be to allocate on the correct host.
    //
    InetAddress inetAddress = InetAddress.getByName( hostname );
    String address = inetAddress.getHostAddress();

    String service = AllocateServerSocketServlet.CONTEXT_PATH + "/?";
    service += AllocateServerSocketServlet.PARAM_RANGE_START + "=" + Integer.toString( portRangeStart );
    service += "&" + AllocateServerSocketServlet.PARAM_ID + "=" + URLEncoder.encode( runId, "UTF-8" );
    service += "&" + AllocateServerSocketServlet.PARAM_HOSTNAME + "=" + address;
    service +=
      "&" + AllocateServerSocketServlet.PARAM_TRANSFORMATION_NAME + "="
        + URLEncoder.encode( transformationName, "UTF-8" );
    service +=
      "&" + AllocateServerSocketServlet.PARAM_SOURCE_SLAVE + "=" + URLEncoder.encode( sourceSlaveName, "UTF-8" );
    service +=
      "&" + AllocateServerSocketServlet.PARAM_SOURCE_STEPNAME + "=" + URLEncoder.encode( sourceStepName, "UTF-8" );
    service +=
      "&" + AllocateServerSocketServlet.PARAM_SOURCE_STEPCOPY + "=" + URLEncoder.encode( sourceStepCopy, "UTF-8" );
    service +=
      "&" + AllocateServerSocketServlet.PARAM_TARGET_SLAVE + "=" + URLEncoder.encode( targetSlaveName, "UTF-8" );
    service +=
      "&" + AllocateServerSocketServlet.PARAM_TARGET_STEPNAME + "=" + URLEncoder.encode( targetStepName, "UTF-8" );
    service +=
      "&" + AllocateServerSocketServlet.PARAM_TARGET_STEPCOPY + "=" + URLEncoder.encode( targetStepCopy, "UTF-8" );
    service += "&xml=Y";
    String xml = execService( service );
    Document doc = XMLHandler.loadXMLString( xml );
    String portString = XMLHandler.getTagValue( doc, AllocateServerSocketServlet.XML_TAG_PORT );

    int port = Const.toInt( portString, -1 );
    if ( port < 0 ) {
      throw new Exception( "Unable to retrieve port from service : " + service + ", received : \n" + xml );
    }

    return port;
  }

  public String getName() {
    lock.readLock().lock();
    try {
      return name;
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setName( String name ) {
    lock.writeLock().lock();
    this.name = name;
    lock.writeLock().unlock();
  }

  public boolean isShared() {
    lock.readLock().lock();
    try {
      return shared;
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setShared( boolean shared ) {
    lock.writeLock().lock();
    this.shared = shared;
    lock.writeLock().unlock();
  }

  public void copyVariablesFrom( VariableSpace space ) {
    variables.copyVariablesFrom( space );
  }

  public String environmentSubstitute( String aString ) {
    return variables.environmentSubstitute( aString );
  }

  public String[] environmentSubstitute( String[] aString ) {
    return variables.environmentSubstitute( aString );
  }

  public String fieldSubstitute( String aString, RowMetaInterface rowMeta, Object[] rowData )
    throws KettleValueException {
    return variables.fieldSubstitute( aString, rowMeta, rowData );
  }

  public VariableSpace getParentVariableSpace() {
    return variables.getParentVariableSpace();
  }

  public void setParentVariableSpace( VariableSpace parent ) {
    variables.setParentVariableSpace( parent );
  }

  public String getVariable( String variableName, String defaultValue ) {
    return variables.getVariable( variableName, defaultValue );
  }

  public String getVariable( String variableName ) {
    return variables.getVariable( variableName );
  }

  public boolean getBooleanValueOfVariable( String variableName, boolean defaultValue ) {
    if ( !Const.isEmpty( variableName ) ) {
      String value = environmentSubstitute( variableName );
      if ( !Const.isEmpty( value ) ) {
        return ValueMetaString.convertStringToBoolean( value );
      }
    }
    return defaultValue;
  }

  public void initializeVariablesFrom( VariableSpace parent ) {
    variables.initializeVariablesFrom( parent );
  }

  public String[] listVariables() {
    return variables.listVariables();
  }

  public void setVariable( String variableName, String variableValue ) {
    variables.setVariable( variableName, variableValue );
  }

  public void shareVariablesWith( VariableSpace space ) {
    variables = space;
  }

  public void injectVariables( Map<String, String> prop ) {
    variables.injectVariables( prop );
  }

  public ObjectId getObjectId() {
    lock.readLock().lock();
    try {
      return id;
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setObjectId( ObjectId id ) {
    lock.writeLock().lock();
    this.id = id;
    lock.writeLock().unlock();
  }

  /**
   * Not used in this case, simply return root /
   */
  public RepositoryDirectoryInterface getRepositoryDirectory() {
    return new RepositoryDirectory();
  }

  public void setRepositoryDirectory( RepositoryDirectoryInterface repositoryDirectory ) {
    throw new RuntimeException( "Setting a directory on a database connection is not supported" );
  }

  public RepositoryObjectType getRepositoryElementType() {
    return REPOSITORY_ELEMENT_TYPE;
  }

  public ObjectRevision getObjectRevision() {
    lock.readLock().lock();
    try {
      return objectRevision;
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setObjectRevision( ObjectRevision objectRevision ) {
    lock.writeLock().lock();
    this.objectRevision = objectRevision;
    lock.writeLock().unlock();
  }

  public String getDescription() {
    // NOT USED
    return null;
  }

  public void setDescription( String description ) {
    // NOT USED
  }

  /**
   * Verify the name of the slave server and if required, change it if it already exists in the list of slave servers.
   *
   * @param slaveServers
   *          the slave servers to check against.
   * @param oldname
   *          the old name of the slave server
   * @return the new slave server name
   */
  public String verifyAndModifySlaveServerName( List<SlaveServer> slaveServers, String oldname ) {
    String name = getName();
    if ( name.equalsIgnoreCase( oldname ) ) {
      return name; // nothing to see here: move along!
    }

    int nr = 2;
    while ( SlaveServer.findSlaveServer( slaveServers, getName() ) != null ) {
      setName( name + " " + nr );
      nr++;
    }
    return getName();
  }

  /**
   * Sniff rows on a the slave server, return xml containing the row metadata and data.
   *
   * @param transName transformation name
   * @param stepName  step name
   * @param copyNr    step copy number
   * @param lines     lines number
   * @param type      step type
   * @return xml with row metadata and data
   * @throws Exception
   */
  public String sniffStep( String transName, String stepName, String copyNr, int lines, String type ) throws Exception {
    return execService( SniffStepServlet.CONTEXT_PATH + "/?trans=" + URLEncoder.encode( transName, "UTF-8" ) + "&step="
      + URLEncoder.encode( stepName, "UTF-8" ) + "&copynr=" + copyNr + "&type=" + type + "&lines=" + lines + "&xml=Y" );
  }

  public long getNextSlaveSequenceValue( String slaveSequenceName, long incrementValue ) throws KettleException {
    try {
      String xml =
          execService( NextSequenceValueServlet.CONTEXT_PATH + "/" + "?" + NextSequenceValueServlet.PARAM_NAME + "="
              + URLEncoder.encode( slaveSequenceName, "UTF-8" ) + "&" + NextSequenceValueServlet.PARAM_INCREMENT + "="
              + Long.toString( incrementValue ) );

      Document doc = XMLHandler.loadXMLString( xml );
      Node seqNode = XMLHandler.getSubNode( doc, NextSequenceValueServlet.XML_TAG );
      String nextValueString = XMLHandler.getTagValue( seqNode, NextSequenceValueServlet.XML_TAG_VALUE );
      String errorString = XMLHandler.getTagValue( seqNode, NextSequenceValueServlet.XML_TAG_ERROR );

      if ( !Const.isEmpty( errorString ) ) {
        throw new KettleException( errorString );
      }
      if ( Const.isEmpty( nextValueString ) ) {
        throw new KettleException( "No value retrieved from slave sequence '" + slaveSequenceName + "' on slave "
          + toString() );
      }
      long nextValue = Const.toLong( nextValueString, Long.MIN_VALUE );
      if ( nextValue == Long.MIN_VALUE ) {
        throw new KettleException( "Incorrect value '" + nextValueString + "' retrieved from slave sequence '"
          + slaveSequenceName + "' on slave " + toString() );
      }

      return nextValue;
    } catch ( Exception e ) {
      throw new KettleException( "There was a problem retrieving a next sequence value from slave sequence '"
        + slaveSequenceName + "' on slave " + toString(), e );
    }
  }

  public SlaveServer getClient() {
    lock.readLock().lock();
    try {
      String pHostName = getHostname();
      String pPort = getPort();
      String name = MessageFormat.format( "Dynamic slave [{0}:{1}]", pHostName, pPort );
      SlaveServer client = new SlaveServer( name, pHostName, pPort, getUsername(), getPassword() );
      client.setSslMode( isSslMode() );
      return client;
    } finally {
      lock.readLock().unlock();
    }

  }

  /**
   * @return the changedDate
   */
  public Date getChangedDate() {
    lock.readLock().lock();
    try {
      return changedDate;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @param sslMode
   */
  public void setSslMode( boolean sslMode ) {
    lock.writeLock().lock();
    this.sslMode = sslMode;
    lock.writeLock().unlock();
  }

  /**
   * @return the sslMode
   */
  public boolean isSslMode() {
    lock.readLock().lock();
    try {
      return sslMode;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @return the sslConfig
   */
  public SslConfiguration getSslConfig() {
    lock.readLock().lock();
    try {
      return sslConfig;
    } finally {
      lock.readLock().unlock();
    }
  }
}

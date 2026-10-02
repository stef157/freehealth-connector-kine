package org.taktik.connector.technical.handler;

import org.taktik.connector.technical.config.ConfigFactory;
import org.taktik.connector.technical.config.Configuration;
import java.util.concurrent.TimeUnit;
import jakarta.xml.ws.handler.soap.SOAPMessageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectionTimeOutHandler extends AbstractSOAPHandler {
   private static final Logger LOG = LoggerFactory.getLogger(ConnectionTimeOutHandler.class);
   private static final String REQUEST_TIMEOUT = "com.sun.xml.internal.ws.request.timeout";
   private static final String CONNECT_TIMEOUT = "com.sun.xml.internal.ws.connect.timeout";
   private static final String REQUEST_TIMEOUT_PROP = "connector.soaphandler.connection.request.timeout";
   private static final String CONNECT_TIMEOUT_PROP = "connector.soaphandler.connection.connection.timeout";
   private Configuration config = ConfigFactory.getConfigValidator();

   public boolean handleOutbound(SOAPMessageContext context) {
      String endpointProperty = endpointProperty((String) context.get("jakarta.xml.ws.service.endpoint.address"));

      String requestTimeOut = this.getDuration(REQUEST_TIMEOUT_PROP, endpointProperty);
      LOG.debug("Setting request timeout on: {} milliseconds.", requestTimeOut);
      context.put(REQUEST_TIMEOUT, requestTimeOut);
      context.put(REQUEST_TIMEOUT_PROP, requestTimeOut);

      String connectTimeOut = this.getDuration(CONNECT_TIMEOUT_PROP, endpointProperty);
      LOG.debug("Setting connect timeout on: {} milliseconds.", connectTimeOut);
      context.put(CONNECT_TIMEOUT, connectTimeOut);
      context.put(CONNECT_TIMEOUT_PROP, connectTimeOut);
      return true;
   }

   /**
    * Suffix of the per-endpoint timeout keys: the last two segments of the endpoint URL, e.g.
    * {@code …/GenAsync/eagreement} → {@code GenAsync.eagreement}. A key whose suffix no endpoint produces is silently
    * ignored and the default applies.
    */
   static String endpointProperty(String endpoint) {
      String[] parts = endpoint != null ? endpoint.split("/+") : null;
      return (parts != null && parts.length>=4) ? parts[parts.length-2] + "." + parts[parts.length-1] : null;
   }

   private String getDuration(String timeoutProp, String endpointProperty) {
      String endpointTimeoutProp = endpointProperty != null ? timeoutProp + "." + endpointProperty : null;
      if (endpointTimeoutProp != null && (this.config.hasProperty(endpointTimeoutProp) || this.config.hasDurationProperty(endpointTimeoutProp))) {
         return this.config.hasDurationProperty(endpointTimeoutProp) ? Long.toString(this.config.getDurationProperty(endpointTimeoutProp, 60L, TimeUnit.SECONDS).convert(TimeUnit.MILLISECONDS)) : this.config.getProperty(endpointTimeoutProp, "60000");
      }
      return this.config.hasDurationProperty(timeoutProp) ? Long.toString(this.config.getDurationProperty(timeoutProp, 60L, TimeUnit.SECONDS).convert(TimeUnit.MILLISECONDS)) : this.config.getProperty(timeoutProp, "60000");
   }

   public boolean handleFault(SOAPMessageContext context) {
      return this.handleMessage(context);
   }
}

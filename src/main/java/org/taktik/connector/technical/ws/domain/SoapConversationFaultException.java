package org.taktik.connector.technical.ws.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import jakarta.xml.soap.SOAPFault;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.soap.SOAPFaultException;

/**
 * A SOAP fault that keeps the conversation it ended: the envelope that left and the fault envelope that came back.
 *
 * `GenericResponse.getSOAPException()` used to throw a bare `SOAPFaultException`, so a fault reached the controller
 * with its fault string only — the request and the fault were dropped, and a refused call (SOA-01002 on eAgreement,
 * a rejected eFact or eAttest send) left nothing to debug and nothing to show the CIN. This subclass carries both,
 * serialized when the fault is thrown, while `GenericResponse` still holds them.
 *
 * A subclass, so every existing `catch (SOAPFaultException)` keeps working unchanged.
 *
 * ⚠️ `getSoapRequest()` is the envelope that left, and it carries the CIN licence in clear, exactly like
 * `mycarenetConversation.soapRequest` on a success. The envelopes are **never** part of `getMessage()` or
 * `toString()`: those reach logs and Sentry. A client that stores them must redact `<License>`.
 */
public class SoapConversationFaultException extends SOAPFaultException {
   private static final long serialVersionUID = 1L;

   private final transient String soapRequest;
   private final transient String soapResponse;

   public SoapConversationFaultException(SOAPFault fault, SOAPMessage request, SOAPMessage response) {
      super(fault);
      this.soapRequest = serialize(request);
      this.soapResponse = serialize(response);
   }

   /** The envelope that left, or null when the response was built without it. */
   public String getSoapRequest() {
      return soapRequest;
   }

   /** The fault envelope that came back. */
   public String getSoapResponse() {
      return soapResponse;
   }

   private static String serialize(SOAPMessage message) {
      if (message == null) {
         return null;
      }

      try {
         ByteArrayOutputStream out = new ByteArrayOutputStream();
         message.writeTo(out);
         return out.toString(StandardCharsets.UTF_8);
      } catch (Exception e) {
         // The fault must still be thrown: an envelope that cannot be written is a missing piece, not a new error.
         return null;
      }
   }
}

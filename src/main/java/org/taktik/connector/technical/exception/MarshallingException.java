package org.taktik.connector.technical.exception;

/**
 * A JAXB (un)marshalling failure in {@link org.taktik.connector.technical.utils.MarshallerHelper}.
 *
 * It stays an {@link IllegalArgumentException} for the callers that already catch one, but it is not the caller's
 * mistake: MarshallerHelper reads every SOAP response, so it is mostly thrown after the request was sent. The REST
 * layer must answer it with a 5xx, never with the 400 an IllegalArgumentException gets, which a client may read as
 * "nothing was sent" and replay.
 */
public class MarshallingException extends IllegalArgumentException {
   private static final long serialVersionUID = 1L;

   public MarshallingException(String message, Throwable cause) {
      super(message, cause);
   }
}

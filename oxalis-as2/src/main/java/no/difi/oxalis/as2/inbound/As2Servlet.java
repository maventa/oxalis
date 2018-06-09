/*
 * Copyright 2010-2018 Norwegian Agency for Public Management and eGovernment (Difi)
 *
 * Licensed under the EUPL, Version 1.1 or – as soon they
 * will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 *
 * You may not use this work except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/community/eupl/og_page/eupl
 *
 * Unless required by applicable law or agreed to in
 * writing, software distributed under the Licence is
 * distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the Licence for the specific language governing
 * permissions and limitations under the Licence.
 */

package no.difi.oxalis.as2.inbound;

import brave.Span;
import brave.Tracer;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import no.difi.oxalis.api.error.ErrorTracker;
import no.difi.oxalis.api.model.Direction;
import no.difi.oxalis.as2.code.As2Header;
import no.difi.oxalis.as2.code.MdnHeader;
import no.difi.oxalis.as2.lang.OxalisAs2InboundException;
import no.difi.oxalis.as2.util.MdnBuilder;
import no.difi.oxalis.as2.util.MimeMessageHelper;
import no.difi.oxalis.as2.util.SMimeMessageFactory;
import no.difi.oxalis.as2.util.SMimeReader;
import no.difi.oxalis.commons.security.CertificateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeMessage;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author steinar
 * @author thore
 * @author erlend
 */
@Singleton
class As2Servlet extends HttpServlet {

    public static final Logger LOGGER = LoggerFactory.getLogger(As2Servlet.class);

    private final Provider<As2InboundHandler> inboundHandlerProvider;

    private final SMimeMessageFactory sMimeMessageFactory;

    private final ErrorTracker errorTracker;

    private final String toIdentifier;

    private final Tracer tracer;

    @Inject
    public As2Servlet(Provider<As2InboundHandler> inboundHandlerProvider, SMimeMessageFactory sMimeMessageFactory,
                      ErrorTracker errorTracker, X509Certificate certificate, Tracer tracer) {
        this.inboundHandlerProvider = inboundHandlerProvider;
        this.sMimeMessageFactory = sMimeMessageFactory;
        this.errorTracker = errorTracker;
        this.toIdentifier = CertificateUtils.extractCommonName(certificate);
        this.tracer = tracer;
    }

    /**
     * Receives the POST'ed AS2 message.
     * <p>
     * Important to note that the HTTP headers contains the MIME headers for the payload.
     * Since the the request can only be read once, using getReader()/getInputStream()
     */
    @Override
    protected void doPost(final HttpServletRequest request, final HttpServletResponse response)
            throws IOException {

        // Fail fast when Message-Id header is not provided.
        if (request.getHeader("message-id") == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println("Header field 'Message-ID' not found.");
            return;
        }

        // Fail fast when AS2-To is not provided or not addressed to me.
        if (request.getHeader("as2-to") == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println("Header field 'AS2-To' not found.");
            return;
        } else if (!toIdentifier.equals(request.getHeader("as2-to").replace("\"", "").trim())) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println("Invalid value in field 'AS2-To'.");
            return;
        }

        Span root = tracer.newTrace().name("as2servlet.post").start();
        root.tag("message-id", request.getHeader("message-id"));

        MDC.put("message-id", request.getHeader("message-id"));

        // Read all headers
        InternetHeaders headers = new InternetHeaders();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.addHeader(name, request.getHeader(name)));

        // Receives the data, validates the headers, signature etc., invokes the persistence handler
        // and finally returns the MdnData to be sent back to the caller
        try {
            // Read MIME message
            MimeMessage mimeMessage = MimeMessageHelper.parse(request.getInputStream(), headers);

            try {
                // Performs the actual reception of the message by parsing the HTTP POST request
                // persisting the payload etc.

                Span span = tracer.newChild(root.context()).name("as2message").start();
                MimeMessage mdn = inboundHandlerProvider.get().receive(headers, mimeMessage);
                span.finish();

                // Returns the MDN
                span = tracer.newChild(root.context()).name("mdn").start();
                writeMdn(response, mdn, HttpServletResponse.SC_OK);
                span.finish();

            } catch (OxalisAs2InboundException e) {
                String identifier = errorTracker.track(Direction.IN, e, true);

                root.tag("identifier", identifier);
                root.tag("exception", String.valueOf(e.getMessage()));

                // Open message for reading
                SMimeReader sMimeReader = new SMimeReader(mimeMessage);

                // Begin builder
                MdnBuilder mdnBuilder = MdnBuilder.newInstance(mimeMessage);

                // Original Message-Id
                mdnBuilder.addHeader(MdnHeader.ORIGINAL_MESSAGE_ID, headers.getHeader(As2Header.MESSAGE_ID)[0]);

                // Disposition from exception
                mdnBuilder.addHeader(MdnHeader.DISPOSITION, e.getDisposition());
                mdnBuilder.addText(String.format("Error [%s]", identifier), e.getMessage());

                // Build and add headers
                MimeMessage mdn = sMimeMessageFactory.createSignedMimeMessage(
                        mdnBuilder.build(), sMimeReader.getDigestMethod());
                mdn.setHeader(As2Header.AS2_VERSION, As2Header.VERSION);
                mdn.setHeader(As2Header.AS2_FROM, toIdentifier);
                mdn.setHeader(As2Header.AS2_TO, headers.getHeader(As2Header.AS2_FROM)[0]);

                writeMdn(response, mdn, HttpServletResponse.SC_BAD_REQUEST);
            }
        } catch (Exception e) {
            String identifier = errorTracker.track(Direction.IN, e, false);

            root.tag("identifier", identifier);
            root.tag("exception", String.valueOf(e.getMessage()));

            // Unexpected internal error, cannot proceed, return HTTP 500 and partly MDN to indicating the problem
            writeFailureWithExplanation(request, response, e);
        }

        MDC.clear();
        root.finish();
    }

    protected void writeMdn(HttpServletResponse response, MimeMessage mdn, int status)
            throws MessagingException, IOException {
        // Set HTTP status.
        response.setStatus(status);

        // Add headers and collect header names.
        Map<String, String> headers = Collections.list((Enumeration<? extends Object>) mdn.getAllHeaders()).stream()
                .map(Header.class::cast)
                .collect(Collectors.toMap(Header::getName, Header::getValue));

        // Move headers
        for (String name : headers.keySet()) {
            response.setHeader(name, headers.get(name));
            mdn.removeHeader(name);
        }

        // Write MDN content to response.
        ByteStreams.copy(mdn.getInputStream(), response.getOutputStream());
    }

    /**
     * If the AS2 message processing failed with an exception, we have an internal error and act accordingly
     */
    void writeFailureWithExplanation(HttpServletRequest request, HttpServletResponse response, Exception e)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

        LOGGER.debug("Request headers:");
        Collections.list(request.getHeaderNames())
                .forEach(name -> LOGGER.debug("=> {}: {}", name, request.getHeader(name)));

        response.getWriter().write("INTERNAL ERROR!!");
        // Being helpful to those who must read the error logs
        LOGGER.error("\n---------- REQUEST FAILURE INFORMATION ENDS HERE --------------");
    }

    /**
     * Allows for simple http GET requests
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.getOutputStream().println("Hello AS2 world\n");
    }
}

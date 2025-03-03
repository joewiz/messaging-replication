/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2013 The eXist Project
 *  http://exist-db.org
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.jms.shared.send;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.dom.memtree.NodeImpl;
import org.exist.dom.persistent.NodeProxy;
import org.exist.jms.shared.*;
import org.exist.storage.DBBroker;
import org.exist.storage.serializers.Serializer;
import org.exist.validation.internal.node.NodeInputStream;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.*;

import javax.jms.*;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Properties;
import java.util.zip.GZIPOutputStream;

import static org.exist.jms.shared.Constants.*;
import static org.exist.jms.shared.ErrorCodes.*;


/**
 * @author Dannes Wessels
 */
public class Sender {

    private final static Logger LOG = LogManager.getLogger(Sender.class);
    private static final String EXIST_CONNECTION_POOL = "exist.connection.pool";
    private final XQueryContext xQueryContext;

    /**
     * Constructor.
     */
    public Sender() {
        // NOP
        this.xQueryContext = null;
    }

    /**
     * Constructor.
     *
     * @param context Xquery context, can be NULL for eXistMessageItem. Context will be copied
     */
    public Sender(final XQueryContext context) {
        this.xQueryContext = context.copyContext();
    }

    /**
     * Send content to JMS broker.
     *
     * @param jmsConfig    JMS configuration
     * @param msgMetaProps JMS message properties
     * @param content      The content to be transferred
     * @return Report
     * @throws XPathException Something bad happened.
     */
    public NodeImpl send(final JmsConfiguration jmsConfig, final JmsMessageProperties msgMetaProps, final Item content) throws XPathException {

        // JMS specific checks
        jmsConfig.validate();

        // Retrieve and set JMS identifier
        final String id = Identity.getInstance().getIdentity();
        if (StringUtils.isNotBlank(id)) {
            msgMetaProps.setProperty(Constants.EXIST_INSTANCE_ID, id);
        } else {
            LOG.error("An empty value was provided for '{}'", Constants.EXIST_INSTANCE_ID);
        }

        // Set username
        if (xQueryContext != null) {
            final String username = xQueryContext.getSubject().getName();
            if (username != null) {
                msgMetaProps.setProperty("exist.user", username);
            }
        }

        // Retrieve relevant values
        final String initialContextFactory = jmsConfig.getInitialContextFactory();
        final String providerURL = jmsConfig.getBrokerURL();
        final String destinationValue = jmsConfig.getDestination();

        Connection connection = null;
        try {
            final Properties props = new Properties();
            props.setProperty(Context.INITIAL_CONTEXT_FACTORY, initialContextFactory);
            props.setProperty(Context.PROVIDER_URL, providerURL);
            final javax.naming.Context context = new InitialContext(props);

            // Get connection factory
            final ConnectionFactory cf = getConnectionFactoryInstance(context, jmsConfig);

            if (cf == null) {
                throw new XPathException(JMS026, "Unable to create connection factory");
            }

            // Setup username/password when required
            final String userName = jmsConfig.getConnectionUserName();
            final String password = jmsConfig.getConnectionPassword();

            connection = (StringUtils.isBlank(userName) || StringUtils.isBlank(password))
                    ? cf.createConnection()
                    : cf.createConnection(userName, password);

            // Set clientId when set and not empty
            final String clientId = jmsConfig.getClientId();
            if (StringUtils.isNotBlank(clientId)) {
                connection.setClientID(clientId);
            }

            // Lookup queue
            final Destination destination = (Destination) context.lookup(destinationValue);

            // Create session
            final Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);

            // Create message producer
            final MessageProducer messageProducer = session.createProducer(destination);

            // Create message, depending on incoming object type
            final boolean isExistMessageItem = (content instanceof eXistMessageItem);
            final Message message = isExistMessageItem
                    ? createMessageFromExistMessageItem(session, (eXistMessageItem) content, msgMetaProps)
                    : createMessageFromItem(session, content, msgMetaProps);

            // Set Message properties from user provided data
            setMessagePropertiesFromMap(msgMetaProps, message);

            // Set time-to-live (when available)
            final Long timeToLive = jmsConfig.getTimeToLive();
            if (timeToLive != null) {
                messageProducer.setTimeToLive(timeToLive);
            }

            // Set priority (when available)
            final Integer priority = jmsConfig.getPriority();
            if (priority != null) {
                messageProducer.setPriority(priority);
            }

            // Set deliveryMethod (when available)
            final Integer deliveryMethod = jmsConfig.getDeliveryMethod();
            if (deliveryMethod != null) {
                messageProducer.setDeliveryMode(deliveryMethod);
            }

            // Send message
            messageProducer.send(message);

            // Return report
            return createReport(message, messageProducer, jmsConfig);

        } catch (final JMSException ex) {
            LOG.error(ex.getMessage(), ex);

            final Throwable cause = ex.getCause();

            if ("Error while attempting to add new Connection to the pool".contentEquals(ex.getMessage()) && cause != null) {
                throw new XPathException(JMS004, cause.getMessage());

            } else {
                throw new XPathException(JMS004, ex.getMessage());
            }

        } catch (final Throwable ex) {
            LOG.error(ex.getMessage(), ex);
            throw new XPathException(JMS000, ex.getMessage());

        } finally {
            try {
                if (connection != null) {
                    // Close connection
                    connection.close();
                }
            } catch (final JMSException ex) {
                LOG.error("Problem closing connection, ignored. {} ({})", ex.getMessage(), ex.getErrorCode());
            }
        }
    }

    /**
     * Get connection factory
     */
    private ConnectionFactory getConnectionFactoryInstance(final javax.naming.Context context, final JmsConfiguration jmsConfig) throws NamingException {

        final ConnectionFactory retVal;

        // Use pooling when
        final String poolValue = jmsConfig.getProperty(EXIST_CONNECTION_POOL, "activemq");
        if (StringUtils.isNotBlank(poolValue)) {

            // Get URL to broker
            final String providerURL = jmsConfig.getBrokerURL();

            // Get ConnectionFactory
            retVal = SenderConnectionFactory.getConnectionFactoryInstance(providerURL, poolValue);

        } else {
            // Get name of connection factory
            final String connectionFactory = jmsConfig.getConnectionFactory();

            // Get connection factory, the context already contains the brokerURL.
            retVal = (ConnectionFactory) context.lookup(connectionFactory);
        }

        return retVal;
    }

    /**
     * Convert messaging-function originated data into a JMS message.
     *
     * @param session The JMS session
     * @param item    The XQuery item containing data
     * @param jmp     JMS message properties
     * @return JMS message
     * @throws JMSException   When a problem occurs in the JMS domain
     * @throws XPathException When an other issue occurs
     */
    private Message createMessageFromItem(final Session session, final Item item, final JmsMessageProperties jmp) throws JMSException, XPathException {

        final Message message;

        jmp.setProperty(EXIST_XPATH_DATATYPE, Type.getTypeName(item.getType()));
        final boolean isCompressed = applyGZIPcompression(jmp);

        switch (item.getType()) {
            case Type.ELEMENT:
            case Type.DOCUMENT: {
                LOG.debug("Streaming element or document node");

                jmp.setProperty(EXIST_DATA_TYPE, DATA_TYPE_XML);

                if (item instanceof NodeProxy) {
                    final NodeProxy np = (NodeProxy) item;
                    jmp.setProperty(EXIST_DOCUMENT_URI, np.getDoc().getBaseURI());
                    jmp.setProperty(EXIST_DOCUMENT_MIMETYPE, np.getDoc().getMetadata().getMimeType());
                }

                final ByteArrayOutputStream baos = new ByteArrayOutputStream();

                // Stream content node to buffer
                final NodeValue node = (NodeValue) item;

                // note: this code is not responsible to close the broker!
                final DBBroker broker = xQueryContext.getBroker();

                final Serializer serializer = broker.newSerializer();
                try (InputStream is = new NodeInputStream(serializer, node);

                     // Compress data when indicated
                     OutputStream os = getOutputStream(isCompressed, baos)) {

                    IOUtils.copy(is, os);

                } catch (final IOException ex) {
                    LOG.error(ex.getMessage(), ex);
                    throw new XPathException(JMS001, ex.getMessage(), ex);

                }
//                } catch (EXistException e) {
//                    LOG.error(e);
//                }

                // Create actual message, pass data
                final BytesMessage bytesMessage = session.createBytesMessage();
                bytesMessage.writeBytes(baos.toByteArray());

                // Swap
                message = bytesMessage;

                break;
            }
            case Type.BASE64_BINARY:
            case Type.HEX_BINARY: {

                LOG.debug("Streaming base64 binary");

                jmp.setProperty(EXIST_DATA_TYPE, DATA_TYPE_BINARY);

                if (item instanceof Base64BinaryDocument) {
                    final Base64BinaryDocument b64doc = (Base64BinaryDocument) item;
                    final String uri = b64doc.getUrl();

                    LOG.debug("Base64BinaryDocument detected, adding URL {}", uri);
                    jmp.setProperty(EXIST_DOCUMENT_URI, uri);

                }

                final ByteArrayOutputStream baos = new ByteArrayOutputStream();


                // Copy data from item to buffer
                final BinaryValue binary = (BinaryValue) item;

                try (InputStream is = binary.getInputStream();
                     OutputStream os = getOutputStream(isCompressed, baos)) {

                    IOUtils.copy(is, os);

                } catch (final IOException ex) {
                    LOG.error(ex);
                    throw new XPathException(JMS001, ex.getMessage(), ex);
                }

                // Create actual message, pass data
                final BytesMessage bytesMessage = session.createBytesMessage();
                bytesMessage.writeBytes(baos.toByteArray());

                // Swap
                message = bytesMessage;

                break;
            }
            case Type.STRING:
                // xs:string() is mapped to a TextMessage
                final TextMessage textMessage = session.createTextMessage();
                textMessage.setText(item.getStringValue());
                message = textMessage;


                break;
            default:
                final ObjectMessage objectMessage = session.createObjectMessage();

                switch (item.getType()) {
                    case Type.INTEGER:
                        final BigInteger intValue = item.toJavaObject(BigInteger.class);
                        objectMessage.setObject(intValue);
                        break;
                    case Type.DOUBLE:
                        final Double doubleValue = item.toJavaObject(Double.class);
                        objectMessage.setObject(doubleValue);
                        break;
                    case Type.FLOAT:
                        final Float foatValue = item.toJavaObject(Float.class);
                        objectMessage.setObject(foatValue);
                        break;
                    case Type.DECIMAL:
                        final BigDecimal decimalValue = item.toJavaObject(BigDecimal.class);
                        objectMessage.setObject(decimalValue);
                        break;
                    case Type.BOOLEAN:
                        final Boolean booleanValue = item.toJavaObject(Boolean.class);
                        objectMessage.setObject(booleanValue);
                        break;
                    default:
                        throw new XPathException(JMS027,
                                String.format("Unable to convert '%s' of type '%s' into a JMS object.", item.getStringValue(), item.getType()));
                }

                // Swap
                message = objectMessage;
                break;
        }

        return message;
    }

    private OutputStream getOutputStream(final boolean isCompressed, final ByteArrayOutputStream baos) throws IOException {
        return isCompressed ? new GZIPOutputStream(baos) : baos;
    }

    /**
     * Convert replication originated data into a JMS message.
     *
     * @param session      JMS session
     * @param emi          The data
     * @param msgMetaProps Additional JMS message properties
     * @return JMS Message
     * @throws JMSException When an issue happens
     */
    private Message createMessageFromExistMessageItem(final Session session, final eXistMessageItem emi, final JmsMessageProperties msgMetaProps) throws JMSException {

        // Create bytes message
        final BytesMessage message = session.createBytesMessage();

        // Set payload when available
        final eXistMessage em = emi.getData();

        final byte[] payload = em.getPayload();

        if (payload == null) {
            LOG.debug("No payload for replication");
        } else {
            message.writeBytes(payload);
        }

        em.updateMessageProperties(message);

        return message;
    }

    /**
     * Determine if the XML/Binary payload needs to be compressed
     *
     * @param mdd The JMS message properties
     * @return TRUE if not set or has value 'gzip' else FALSE.
     */
    private boolean applyGZIPcompression(final JmsMessageProperties mdd) {
        // 
        String compressionValue = mdd.getProperty(EXIST_DOCUMENT_COMPRESSION);
        if (StringUtils.isBlank(compressionValue)) {
            compressionValue = COMPRESSION_TYPE_GZIP;
            mdd.setProperty(EXIST_DOCUMENT_COMPRESSION, COMPRESSION_TYPE_GZIP);
        }

        return COMPRESSION_TYPE_GZIP.equals(compressionValue);
    }

    private void setMessagePropertiesFromMap(final JmsMessageProperties msgMetaProps, final Message message) throws JMSException {

        if (msgMetaProps == null) {
            LOG.debug("No JmsMessageProperties was provided");
            return;
        }

        // Write message properties
        for (final Map.Entry<Object, Object> entry : msgMetaProps.entrySet()) {

            final String key = (String) entry.getKey();
            final Object value = entry.getValue();

            if (value instanceof String) {
                message.setStringProperty(key, (String) value);

            } else if (value instanceof Integer) {
                message.setIntProperty(key, (Integer) value);

            } else if (value instanceof Double) {
                message.setDoubleProperty(key, (Double) value);

            } else if (value instanceof Boolean) {
                message.setBooleanProperty(key, (Boolean) value);

            } else if (value instanceof Float) {
                message.setFloatProperty(key, (Float) value);

            } else {
                LOG.error("Cannot set {} into a JMS property", value.getClass().getCanonicalName());
            }
        }
    }

    /**
     * Create messaging results report
     */
    private NodeImpl createReport(final Message message, final MessageProducer producer, final JmsConfiguration config) {

        final MemTreeBuilder builder = new MemTreeBuilder();
        builder.startDocument();

        // start root element
        final int nodeNr = builder.startElement("", JMS, JMS, null);

        /*
         * Message
         */
        if (message != null) {
            try {
                final String txt = message.getJMSMessageID();
                if (txt != null) {
                    builder.startElement("", JMS_MESSAGE_ID, JMS_MESSAGE_ID, null);
                    builder.characters(txt);
                    builder.endElement();
                }
            } catch (final JMSException ex) {
                LOG.error(ex);
            }

            try {
                final String txt = message.getJMSCorrelationID();
                if (txt != null) {
                    builder.startElement("", JMS_CORRELATION_ID, JMS_CORRELATION_ID, null);
                    builder.characters(txt);
                    builder.endElement();
                }
            } catch (final JMSException ex) {
                LOG.error(ex);
            }

            try {
                final String txt = message.getJMSType();
                if (StringUtils.isNotEmpty(txt)) {
                    builder.startElement("", JMS_TYPE, JMS_TYPE, null);
                    builder.characters(txt);
                    builder.endElement();
                }
            } catch (final JMSException ex) {
                LOG.error(ex);
            }
        }

        /*
         * Producer
         */
        if (producer != null) {
            try {
                final long timeToLive = producer.getTimeToLive();
                builder.startElement("", PRODUCER_TTL, PRODUCER_TTL, null);
                builder.characters("" + timeToLive);
                builder.endElement();
            } catch (final JMSException ex) {
                LOG.error(ex);
            }

            try {
                final long priority = producer.getPriority();
                builder.startElement("", PRODUCER_PRIORITY, PRODUCER_PRIORITY, null);
                builder.characters("" + priority);
                builder.endElement();
            } catch (final JMSException ex) {
                LOG.error(ex);
            }
        }

        /*
         * Configuration
         */
        if (config != null) {
            builder.startElement("", Context.INITIAL_CONTEXT_FACTORY, Context.INITIAL_CONTEXT_FACTORY, null);
            builder.characters(config.getInitialContextFactory());
            builder.endElement();

            builder.startElement("", Context.PROVIDER_URL, Context.PROVIDER_URL, null);
            builder.characters(config.getBrokerURL());
            builder.endElement();

            builder.startElement("", Constants.CONNECTION_FACTORY, Constants.CONNECTION_FACTORY, null);
            builder.characters(config.getConnectionFactory());
            builder.endElement();

            builder.startElement("", Constants.DESTINATION, Constants.DESTINATION, null);
            builder.characters(config.getDestination());
            builder.endElement();

            final String userName = config.getConnectionUserName();
            if (StringUtils.isNotBlank(userName)) {
                builder.startElement("", Constants.JMS_CONNECTION_USERNAME, Constants.JMS_CONNECTION_USERNAME, null);
                builder.characters(userName);
                builder.endElement();
            }
        }


        // finish root element
        builder.endElement();

        // return result
        return builder.getDocument().getNode(nodeNr);


    }


}

/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.connection;

import org.bson.types.Binary;
import org.mongodb.Document;
import org.mongodb.MongoCredential;
import org.mongodb.MongoException;
import org.mongodb.codecs.DocumentCodec;
import org.mongodb.command.Command;
import org.mongodb.operation.CommandResult;
import org.mongodb.operation.AsyncCommandOperation;

import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import java.nio.ByteBuffer;

import static org.mongodb.connection.ClusterConnectionMode.Direct;

abstract class SaslAsyncAuthenticator extends AsyncAuthenticator {
    private final BufferPool<ByteBuffer> bufferPool;

    SaslAsyncAuthenticator(final MongoCredential credential, final AsyncConnection connection,
                           final BufferPool<ByteBuffer> bufferPool) {
        super(credential, connection);
        this.bufferPool = bufferPool;
    }

    @Override
    public void authenticate(final SingleResultCallback<CommandResult> callback) {
        final SaslClient saslClient = createSaslClient();
        byte[] response;
        try {
            response = (saslClient.hasInitialResponse() ? saslClient.evaluateChallenge(new byte[0]) : null);
            asyncSendSaslStart(response, new SingleResultCallback<CommandResult>() {
                @Override
                public void onResult(final CommandResult res, final MongoException e) {
                    if (e != null) {
                        disposeOfSaslClient(saslClient);
                        callback.onResult(null, e);
                    }
                    else {
                        final int conversationId = (Integer) res.getResponse().get("conversationId");
                        if ((Boolean) res.getResponse().get("done")) {
                            disposeOfSaslClient(saslClient);
                            callback.onResult(res, null);
                        }
                        else {
                            byte[] response;
                            try {
                                response = saslClient.evaluateChallenge(((Binary) res.getResponse().get("payload")).getData());
                                if (response == null) {
                                    callback.onResult(null, new MongoSecurityException(getCredential(),
                                            "SASL protocol error no client response to challenge for credential " + getCredential()));
                                }
                                else {
                                    asyncSendSaslContinue(conversationId, response, this);
                                }
                            } catch (SaslException saslException) {
                                throw new MongoSecurityException(getCredential(), "Exception authenticating " + getCredential(),
                                        saslException);
                            }
                        }
                    }
                }
            });
        } catch (SaslException e) {
            throw new MongoSecurityException(getCredential(), "Exception authenticating " + getCredential(), e);
        }
    }

    public abstract String getMechanismName();

    protected abstract SaslClient createSaslClient();

    private void asyncSendSaslStart(final byte[] outToken, final SingleResultCallback<CommandResult> callback) {
        new AsyncCommandOperation(getCredential().getSource(), createSaslStartCommand(outToken), new DocumentCodec(),
                new ClusterDescription(Direct), bufferPool).execute(new ConnectingAsyncServerConnection(getConnection()))
                .register(callback);
    }

    private void asyncSendSaslContinue(final int conversationId, final byte[] outToken,
                                       final SingleResultCallback<CommandResult> callback) {
        new AsyncCommandOperation(getCredential().getSource(), createSaslContinueCommand(conversationId, outToken),
                new DocumentCodec(), new ClusterDescription(Direct), bufferPool).execute(
                new ConnectingAsyncServerConnection(getConnection())).register(callback);
    }

    private Command createSaslStartCommand(final byte[] outToken) {
        return new Command(new Document("saslStart", 1).append("mechanism", getMechanismName())
                .append("payload", outToken != null ? outToken : new byte[0]));
    }

    private Command createSaslContinueCommand(final int conversationId, final byte[] outToken) {
        return new Command(new Document("saslContinue", 1).append("conversationId", conversationId).
                append("payload", outToken));
    }

    private void disposeOfSaslClient(final SaslClient saslClient) {
        try {
            saslClient.dispose();
        } catch (SaslException e) { // NOPMD
            // ignore
        }
    }
}
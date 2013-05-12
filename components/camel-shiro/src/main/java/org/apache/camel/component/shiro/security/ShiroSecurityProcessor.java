/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.shiro.security;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;

import org.apache.camel.AsyncCallback;
import org.apache.camel.CamelAuthorizationException;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.processor.DelegateAsyncProcessor;
import org.apache.camel.util.ExchangeHelper;
import org.apache.camel.util.IOHelper;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.LockedAccountException;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.codec.Base64;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ByteSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Processor} that executes the authentication and authorization of the {@link Subject} accordingly
 * to the {@link ShiroSecurityPolicy}.
 */
public class ShiroSecurityProcessor extends DelegateAsyncProcessor {

    private static final transient Logger LOG = LoggerFactory.getLogger(ShiroSecurityProcessor.class);
    private final ShiroSecurityPolicy policy;

    public ShiroSecurityProcessor(Processor processor, ShiroSecurityPolicy policy) {
        super(processor);
        this.policy = policy;
    }

    @Override
    protected boolean processNext(Exchange exchange, AsyncCallback callback) {
        try {
            applySecurityPolicy(exchange);
        } catch (Exception e) {
            // exception occurred so break out
            exchange.setException(e);
            callback.done(true);
            return true;
        }

        return super.processNext(exchange, callback);
    }

    private void applySecurityPolicy(Exchange exchange) throws Exception {
        ByteSource encryptedToken;
        if (policy.isBase64()) {
            String base64 = ExchangeHelper.getMandatoryHeader(exchange, ShiroConstants.SHIRO_SECURITY_TOKEN, String.class);
            byte[] bytes = Base64.decode(base64);
            encryptedToken = ByteSource.Util.bytes(bytes);
        } else {
            encryptedToken = ExchangeHelper.getMandatoryHeader(exchange, ShiroConstants.SHIRO_SECURITY_TOKEN, ByteSource.class);
        }

        ByteSource decryptedToken = policy.getCipherService().decrypt(encryptedToken.getBytes(), policy.getPassPhrase());

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(decryptedToken.getBytes());
        ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
        ShiroSecurityToken securityToken;
        try {
            securityToken = (ShiroSecurityToken)objectInputStream.readObject();
        } finally {
            IOHelper.close(objectInputStream, byteArrayInputStream);
        }

        Subject currentUser = SecurityUtils.getSubject();

        // Authenticate user if not authenticated
        try {
            authenticateUser(currentUser, securityToken);

            // Test whether user's role is authorized to perform functions in the permissions list
            authorizeUser(currentUser, exchange);
        } finally {
            if (policy.isAlwaysReauthenticate()) {
                currentUser.logout();
            }
        }
    }

    private void authenticateUser(Subject currentUser, ShiroSecurityToken securityToken) {
        boolean authenticated = currentUser.isAuthenticated();
        boolean sameUser = securityToken.getUsername().equals(currentUser.getPrincipal());
        LOG.trace("Authenticated: {}, same Username: {}", authenticated, sameUser);

        if (!authenticated || !sameUser) {
            UsernamePasswordToken token = new UsernamePasswordToken(securityToken.getUsername(), securityToken.getPassword());
            if (policy.isAlwaysReauthenticate()) {
                token.setRememberMe(false);
            } else {
                token.setRememberMe(true);
            }

            try {
                currentUser.login(token);
                LOG.debug("Current user {} successfully authenticated", currentUser.getPrincipal());
            } catch (UnknownAccountException uae) {
                throw new UnknownAccountException("Authentication Failed. There is no user with username of " + token.getPrincipal(), uae.getCause());
            } catch (IncorrectCredentialsException ice) {
                throw new IncorrectCredentialsException("Authentication Failed. Password for account " + token.getPrincipal() + " was incorrect!", ice.getCause());
            } catch (LockedAccountException lae) {
                throw new LockedAccountException("Authentication Failed. The account for username " + token.getPrincipal() + " is locked."
                        + "Please contact your administrator to unlock it.", lae.getCause());
            } catch (AuthenticationException ae) {
                throw new AuthenticationException("Authentication Failed.", ae.getCause());
            }
        }
    }

    private void authorizeUser(Subject currentUser, Exchange exchange) throws CamelAuthorizationException {
        boolean authorized = false;
        if (!policy.getPermissionsList().isEmpty()) {
            for (Permission permission : policy.getPermissionsList()) {
                if (currentUser.isPermitted(permission)) {
                    authorized = true;
                    break;
                }
            }
        } else {
            LOG.trace("Valid Permissions List not specified for ShiroSecurityPolicy. No authorization checks will be performed for current user.");
            authorized = true;
        }

        if (!authorized) {
            throw new CamelAuthorizationException("Authorization Failed. Subject's role set does not have the necessary permissions to perform further processing.", exchange);
        }

        LOG.debug("Current user {} is successfully authorized.", currentUser.getPrincipal());
    }

}

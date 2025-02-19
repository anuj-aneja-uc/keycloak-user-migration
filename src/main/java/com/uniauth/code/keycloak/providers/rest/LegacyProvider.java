package com.uniauth.code.keycloak.providers.rest;

import com.uniauth.code.keycloak.providers.rest.remote.LegacyUser;
import com.uniauth.code.keycloak.providers.rest.remote.LegacyUserService;
import com.uniauth.code.keycloak.providers.rest.remote.UserModelFactory;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputUpdater;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserLookupProvider;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Provides legacy user migration functionality
 */
public class LegacyProvider implements UserStorageProvider,
        UserLookupProvider,
        CredentialInputUpdater,
        CredentialInputValidator {

    private static final Logger LOG = Logger.getLogger(LegacyProvider.class);
    private static final Set<String> supportedCredentialTypes = Collections.singleton(PasswordCredentialModel.TYPE);
    private final KeycloakSession   session;
    private final LegacyUserService legacyUserService;
    private final UserModelFactory  userModelFactory;
    private final ComponentModel    model;

    public LegacyProvider(KeycloakSession session, LegacyUserService legacyUserService,
                          UserModelFactory userModelFactory, ComponentModel model) {
        this.session = session;
        this.legacyUserService = legacyUserService;
        this.userModelFactory = userModelFactory;
        this.model = model;
    }

    @Override
    public UserModel getUserByUsername(String username, RealmModel realm) {
        return getUserModel(realm, username, () -> legacyUserService.findByUsername(username));
    }

    private UserModel getUserModel(RealmModel realm, String username, Supplier<Optional<LegacyUser>> user) {
        return user.get()
                .map(u -> userModelFactory.create(u, realm))
                .orElseGet(() -> {
                    LOG.warnf("User not found in external repository: %s", username);
                    return null;
                });
    }

    @Override
    public UserModel getUserByEmail(String email, RealmModel realm) {
        return getUserModel(realm, email, () -> legacyUserService.findByEmail(email));
    }

    @Override
    public boolean isValid(RealmModel realmModel, UserModel userModel, CredentialInput input) {
        if (!supportsCredentialType(input.getType())) {
            return false;
        }

        String userIdentifier = getUserIdentifier(userModel);
        LOG.info(session.userCredentialManager().getStoredCredentialsByTypeStream(realmModel,userModel,"password").map(credentialModel -> credentialModel.getType()+" : "+credentialModel.getCredentialData()).collect(Collectors.toList()));
        long localStorePasswordCount = session.userCredentialManager().getStoredCredentialsByTypeStream(realmModel,userModel,"password").count();
        if (localStorePasswordCount==0 && legacyUserService.isPasswordValid(userIdentifier, input.getChallengeResponse())) {
            LOG.info("Password validated from Provider for user-email: "+userModel.getEmail());
            session.userCredentialManager().updateCredential(realmModel, userModel, input);
            return true;
        }

        return false;
    }

    private String getUserIdentifier(UserModel userModel) {
        String userIdConfig = model.getConfig().getFirst(ConfigurationProperties.USE_USER_ID_FOR_CREDENTIAL_VERIFICATION);
        boolean useUserId = Boolean.parseBoolean(userIdConfig);
        return useUserId ? userModel.getId() : userModel.getUsername();
    }

    @Override
    public boolean supportsCredentialType(String s) {
        return supportedCredentialTypes.contains(s);
    }

    @Override
    public UserModel getUserById(String id, RealmModel realm) {
        throw new UnsupportedOperationException("User lookup by id not implemented");
    }

    @Override
    public boolean isConfiguredFor(RealmModel realmModel, UserModel userModel, String s) {
        return false;
    }

    @Override
    public void close() {
        // Not needed
    }

    @Override
    public boolean updateCredential(RealmModel realm, UserModel user, CredentialInput input) {
        if (!(input instanceof UserCredentialModel))
            return false;
        if (!input.getType().equals(CredentialModel.PASSWORD))
            return false;
        UserCredentialModel cred = (UserCredentialModel) input;
        LOG.info("Updating the password for email: " + user.getEmail() + " and password: " + input.getChallengeResponse());
        legacyUserService.updatePassword(user.getEmail(), cred.getValue());
        return false;
    }

    @Override
    public void disableCredentialType(RealmModel realm, UserModel user, String credentialType) {
        // Not needed
    }

    @Override
    public Set<String> getDisableableCredentialTypes(RealmModel realm, UserModel user) {
        return Collections.emptySet();
    }

}

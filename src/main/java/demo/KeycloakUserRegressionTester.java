package demo;

import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class KeycloakUserRegressionTester {

    public static void main(String[] args) throws Exception {

        String kcUrl = System.getProperty("kc.url", null);
        Objects.requireNonNull(kcUrl, "System property 'kc.url' must not be empty!");

        String kcRealm = System.getProperty("kc.realm", "master");
        String kcClientId = System.getProperty("kc.clientId", "admin-cli");
        String kcClientSecret = System.getProperty("kc.clientSecret", "");
        String kcUsername = System.getProperty("kc.username", "keycloak");
        String kcPassword = System.getProperty("kc.password", "keycloak");

        String kcTargetRealm = System.getProperty("kc.targetRealm", null);
        Objects.requireNonNull(kcTargetRealm, "System property 'kc.targetRealm' must not be empty!");

        KeycloakClientFacade facade = DefaultKeycloakClientFacade.builder() //
                .setServerUrl(kcUrl)
                .setRealmId(kcRealm) //
                // service account with manage-users role
                .setClientId(kcClientId) //
                .setClientSecret(kcClientSecret) //
                .setUsername(kcUsername) //
                .setPassword(kcPassword) //
                .build();

        String targetRealm = kcTargetRealm;
        int totalUserCount = Integer.getInteger("kc.userCount", 10);
        int threads = Integer.getInteger("kc.clientThreads", 4);
        int usersPerThread = totalUserCount / threads;
        int attributeCount = Integer.getInteger("kc.userAttributes", 0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> createTestUsers(facade, targetRealm, usersPerThread, attributeCount)));
        }

        futures.add(pool.submit(() -> createTestUsers(facade, targetRealm, totalUserCount % usersPerThread, attributeCount)));

        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdownNow();

        System.out.println("Found users: " + facade.getUserCount(targetRealm));

        System.out.println("Press enter key to delete the users again");
        System.in.read();

        List<String> userIds = new ArrayList<>();
        facade.forEachFoundUser(targetRealm, "user-", 100, userInfo -> {
            System.out.println("Recording user for deletion: " + userInfo);
            userIds.add(userInfo.getUserId());
        });

        userIds.stream().forEach(userId -> {
            System.out.println("Deleting user: " + userId);
            facade.deleteUser(targetRealm, new UserReference(userId));
        });
    }

    private static void createTestUsers(KeycloakClientFacade facade, String targetRealm, int userCount, int attributeCount) {

        for (int j = 0; j < userCount; j++) {

            UserInfo user = new UserInfo();
            long id = System.nanoTime();
            user.setUsername("user-" + id);
            user.setFirstname("First" + id);
            user.setLastname("Last" + id);
            user.setEmailAddress("tom+user-" + id + "@localhost");

            if (attributeCount > 0) {
                Map<String, List<String>> attributes = new HashMap<>();
                for (int a = 0; a < attributeCount; a++) {
                    attributes.put("attr" + a, Collections.singletonList("value" + a));
                }
                user.setAttributes(attributes);
            }

            // create new users in realm: demo-many-users
            UserReference userRef = facade.createUser(targetRealm, user);
            System.out.println(userRef);
        }
    }

    interface KeycloakClientFacade {

        long getUserCount(String realmId);

        List<UserInfo> listAllUsers(String realmId);

        UserReference createUser(String realmId, UserInfo userInfo);

        void deleteUser(String realmId, UserReference userReference);

        void forEachFoundUser(String realmId, String search, int batchSize, Consumer<UserInfo> consumer);
    }

    static class UserInfo extends UserReference {

        String username;

        String password;

        String emailAddress;

        String firstname;

        String lastname;

        Map<String, List<String>> attributes = Collections.emptyMap();

        public UserInfo(String userId) {
            super(userId);
        }

        public UserInfo() {
            super(UserReference.UNKNOWN_ID);
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getEmailAddress() {
            return emailAddress;
        }

        public void setEmailAddress(String emailAddress) {
            this.emailAddress = emailAddress;
        }

        public String getFirstname() {
            return firstname;
        }

        public void setFirstname(String firstname) {
            this.firstname = firstname;
        }

        public String getLastname() {
            return lastname;
        }

        public void setLastname(String lastname) {
            this.lastname = lastname;
        }

        public Map<String, List<String>> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, List<String>> attributes) {
            this.attributes = attributes;
        }

        @Override
        public String toString() {
            return "UserInfo{" + "username='" + username + '\'' + ", emailAddress='" + emailAddress + '\''
                    + ", firstname='" + firstname + '\'' + ", lastname='" + lastname + '\'' + ", attributes="
                    + attributes + '}';
        }
    }

    static class UserReference {

        protected final static String UNKNOWN_ID = "-------------";

        private final String userId;

        public UserReference(URI loc) {
            this(extractUserId(loc));
        }

        public UserReference(String userId) {
            this.userId = Objects.equals(userId, UNKNOWN_ID) ? null : Objects.requireNonNull(userId);
        }

        private static String extractUserId(URI uri) {
            String path = uri.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        }

        public String getUserId() {
            return userId;
        }

        @Override
        public String toString() {
            return "UserReference{" + "userId='" + userId + '\'' + '}';
        }
    }

    static class DefaultKeycloakClientFacade implements KeycloakClientFacade {

        private final Keycloak keycloak;

        public DefaultKeycloakClientFacade(Keycloak keycloak) {
            this.keycloak = keycloak;
        }

        @Override
        public long getUserCount(String realmId) {
            return keycloak.realm(realmId).users().count();
        }

        @Override
        public List<UserInfo> listAllUsers(String realmId) {

            List<UserRepresentation> results = keycloak.realm(realmId).users().search(null, 0, Integer.MAX_VALUE);
            return results.stream().map(DefaultKeycloakClientFacade::toUserInfo).collect(Collectors.toList());
        }

        @Override
        public void forEachFoundUser(String realmId, String search, int batchSize, Consumer<UserInfo> consumer) {

            int currentIndex = 0;
            while (true) {
                List<UserRepresentation> results = keycloak.realm(realmId).users().search(search, currentIndex, batchSize);
                if (results.isEmpty()) {
                    break;
                }

                results.stream().map(DefaultKeycloakClientFacade::toUserInfo).forEach(consumer);

                if (results.size() < batchSize) {
                    break;
                }

                currentIndex += batchSize;
            }
        }

        private static UserInfo toUserInfo(UserRepresentation userRep) {

            UserInfo userInfo = new UserInfo(userRep.getId());
            userInfo.setUsername(userRep.getUsername());
            userInfo.setFirstname(userRep.getFirstName());
            userInfo.setLastname(userRep.getLastName());
            userInfo.setEmailAddress(userRep.getEmail());
            userInfo.setAttributes(userRep.getAttributes());
            return userInfo;
        }

        @Override
        public UserReference createUser(String realmId, UserInfo userInfo) {

            UserRepresentation ur = new UserRepresentation();
            ur.setEnabled(true);
            ur.setUsername(userInfo.getUsername());
            ur.setFirstName(userInfo.getFirstname());
            ur.setLastName(userInfo.getLastname());
            ur.setEmail(userInfo.getEmailAddress());
            ur.setAttributes(userInfo.getAttributes());

            CredentialRepresentation password = new CredentialRepresentation();
            password.setValue("password");
            password.setType(CredentialRepresentation.PASSWORD);
            ur.setCredentials(Arrays.asList(password));

            try (ClosableResponseWrapper wrapper = new ClosableResponseWrapper(
                    keycloak.realm(realmId).users().create(ur))) {
                switch (Response.Status.fromStatusCode(wrapper.getResponse().getStatus())) {
                    case CREATED:
                        return new UserReference(wrapper.getResponse().getLocation());
                    default:
                        return null;
                }
            }
        }

        @Override
        public void deleteUser(String realmId, UserReference userReference) {
            try (ClosableResponseWrapper wrapper = new ClosableResponseWrapper(
                    keycloak.realm(realmId).users().delete(userReference.getUserId()))) {
                switch (Response.Status.fromStatusCode(wrapper.getResponse().getStatus())) {
                    case NO_CONTENT:
                        return;
                    default:
                        throw new RuntimeException("DELETE_USER_FAILED");
                }
            }
        }

        class ClosableResponseWrapper implements AutoCloseable {

            private final Response response;

            public ClosableResponseWrapper(Response response) {
                this.response = response;
            }

            public Response getResponse() {
                return response;
            }

            public void close() {
                response.close();
            }
        }

        public static KeycloakClientFacadeBuilder builder() {
            return new KeycloakClientFacadeBuilder();
        }

        static class KeycloakClientFacadeBuilder {

            private String serverUrl;

            private String realmId;

            private String clientId;

            private String clientSecret;

            private String username;

            private String password;

            private ResteasyClient resteasyClient;

            public KeycloakClientFacade build() {

                KeycloakBuilder builder = username == null ? newKeycloakFromClientCredentials()
                        : newKeycloakFromPasswordCredentials(username, password);

                if (resteasyClient != null) {
                    builder = builder.resteasyClient(resteasyClient);
                }

                return new DefaultKeycloakClientFacade(builder.build());
            }

            private KeycloakBuilder newKeycloakFromClientCredentials() {
                return KeycloakBuilder.builder() //
                        .realm(realmId) //
                        .serverUrl(serverUrl)//
                        .clientId(clientId) //
                        .clientSecret(clientSecret) //
                        .grantType(OAuth2Constants.CLIENT_CREDENTIALS);
            }

            private KeycloakBuilder newKeycloakFromPasswordCredentials(String username, String password) {
                return newKeycloakFromClientCredentials() //
                        .username(username) //
                        .password(password) //
                        .grantType(OAuth2Constants.PASSWORD);
            }

            public KeycloakClientFacadeBuilder setServerUrl(String serverUrl) {
                this.serverUrl = serverUrl;
                return this;
            }

            public KeycloakClientFacadeBuilder setRealmId(String realmId) {
                this.realmId = realmId;
                return this;
            }

            public KeycloakClientFacadeBuilder setClientId(String clientId) {
                this.clientId = clientId;
                return this;
            }

            public KeycloakClientFacadeBuilder setClientSecret(String clientSecret) {
                this.clientSecret = clientSecret;
                return this;
            }

            public KeycloakClientFacadeBuilder setUsername(String username) {
                this.username = username;
                return this;
            }

            public KeycloakClientFacadeBuilder setPassword(String password) {
                this.password = password;
                return this;
            }

            public KeycloakClientFacadeBuilder setResteasyClient(ResteasyClient resteasyClient) {
                this.resteasyClient = resteasyClient;
                return this;
            }
        }
    }
}
# Reproduce performance regressions in Keycloak

| Task | 3.4.3.Final  |  4.3.0.Final |
|----------|-------------:|------:|
| List users in the admin-console |  80ms | 6.85s |

## Build 
```
mvn clean package
```

## Run

Create 10000 users with 10 attributes with 32 threads in target realm `master` and Keycloak 3.4.3
```
java \
  -Dkc.url=http://localhost:18080/auth \
  -Dkc.targetRealm=master \
  -Dkc.userCount=10000 \
  -Dkc.userAttributes=10 \
  -Dkc.clientThreads=32 \
  -jar target/kc-user-regression-tester.jar
```

Create 10000 users with 10 attributes with 32 threads in target realm `master` and Keycloak 4.3.0
```
java \
  -Dkc.url=http://localhost:28080/auth \
  -Dkc.targetRealm=master \
  -Dkc.userCount=10000 \
  -Dkc.userAttributes=10 \
  -Dkc.clientThreads=32 \
  -jar target/kc-user-regression-tester.jar
```

## Regression Test environments

## Keycloak 3.4.3.Final with PostgreSQL

``` 
docker rm -f kc343pg
docker rm -f kc343
docker network rm kc343net

docker network create kc343net

docker run \
  -d \
  --name kc343pg \
  --net kc343net \
  -e POSTGRES_DB=keycloak \
  -e POSTGRES_USER=keycloak \
  -e POSTGRES_PASSWORD=password \
  postgres:10.5


docker run \
  -d \
  --name kc343 \
  --net kc343net \
  --link kc343pg:postgres \
  -e POSTGRES_PORT_5432_TCP_ADDR=postgres \
  -e KEYCLOAK_USER=keycloak \
  -e KEYCLOAK_PASSWORD=keycloak \
  -p 18080:8080 \
  jboss/keycloak-postgres:3.4.3.Final

```

## Keycloak 4.3.0.Final with PostgreSQL

```
docker rm -f kc430pg
docker rm -f kc430
docker network rm kc430net

docker network create kc430net

docker run \
  -d \
  --name kc430pg \
  --net kc430net \
  -e POSTGRES_DB=keycloak \
  -e POSTGRES_USER=keycloak \
  -e POSTGRES_PASSWORD=password \
  postgres:10.5

docker run \
  -d \
  --name kc430 \
  --net kc430net \
  --link kc430pg:postgres \
  -e DB_VENDOR=POSTGRES \
  -e KEYCLOAK_USER=keycloak \
  -e KEYCLOAK_PASSWORD=keycloak \
  -p 28080:8080 \
  jboss/keycloak:4.3.0.Final

```

## Cleanup

```
docker rm -f kc343pg
docker rm -f kc343
docker network rm kc343net

docker rm -f kc430pg
docker rm -f kc430
docker network rm kc430net
```
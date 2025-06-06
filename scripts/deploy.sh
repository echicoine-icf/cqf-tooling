#!/usr/bin/env bash

# Fail if any command fails or if there are unbound variables and check syntax
set -euxo pipefail
bash -n "$0"

if [[ "$TRAVIS_BRANCH" != "master" && -z "$TRAVIS_TAG" ]]
then
  echo "Not on the master branch or a git tag. Skipping deploy."
  exit 0
fi

if [[ ! -z "$TRAVIS_TAG" ]]
then
  export MAVEN_PROJECT_VERSION="$(mvn org.apache.maven.plugins:maven-help-plugin:3.2.0:evaluate -Dexpression=project.version -q -DforceStdout)"
  echo "Maven project version is: $MAVEN_PROJECT_VERSION"
  if [[ "$TRAVIS_TAG" != "v$MAVEN_PROJECT_VERSION" ]]
  then
    echo "ERROR: Maven project version and tag do not match (expected tag v$MAVEN_PROJECT_VERSION)"
    exit 1
  fi
fi

# Import maven settings
cp .travis.settings.xml $HOME/.m2/settings.xml

CMD="./mvnw deploy -DskipTests=true -Dmaven.test.skip=true -B"

# Import signing key and publish a release on a tag
if [[ ! -z "$TRAVIS_TAG" ]]
then
    echo "Publishing Maven Central release for tag: $TRAVIS_TAG"
    echo $GPG_SECRET_KEYS | base64 --decode| $GPG_EXECUTABLE --import;
    echo $GPG_OWNERTRUST | base64 --decode | $GPG_EXECUTABLE --import-ownertrust;
    # Activate the release profile
    CMD="$CMD,release"
else
   echo "Publishing Maven Central snapshot / pre-release for branch: $TRAVIS_BRANCH"
fi 

eval $CMD
#!/usr/bin/env bash
# Option not to exit terminal while iterating.
exit_on_error="${EXIT_ON_ERROR:-1}"
if [[ $exit_on_error -ne 0 ]]; then
  set -e
fi
# Important: to update the build number of intellij, you need to update the following hashes:
# Intellij tarball for Community and Ultimate Edition
# Scala plugin
# Python plugin for Community and Ultimate Edition

export CWD=$(pwd)
export IJ_VERSION="2017.3"
export IJ_BUILD_NUMBER="173.3727.78"

get_md5(){
  if [[ $OSTYPE == *"darwin"* ]]; then
    echo  $(md5 $1| awk '{print $NF}')
  else
    echo  $(md5sum $1| awk '{print $1}')
  fi
}

if [[ "${IJ_ULTIMATE:-false}" == "true" ]]; then
  export IJ_BUILD="IU-${IJ_VERSION}"
  export FULL_IJ_BUILD_NUMBER="IU-${IJ_BUILD_NUMBER}"
  export EXPECTED_IJ_MD5="994547db739debaf2eccc6ede78dad5f"
  export PYTHON_PLUGIN_ID="Pythonid"
  export PYTHON_PLUGIN_MD5="c77e47f6ec70fc250e6abbde0896aca4"
else
  export IJ_BUILD="IC-${IJ_VERSION}"
  export FULL_IJ_BUILD_NUMBER="IC-${IJ_BUILD_NUMBER}"
  export EXPECTED_IJ_MD5="21b78e59ef881d97df588c798e4a46fe"
  export PYTHON_PLUGIN_ID="PythonCore"
  export PYTHON_PLUGIN_MD5="00abf2f22839411696da96124b0c93ca"
fi

# we will use Community ids to download plugins.
export SCALA_PLUGIN_ID="org.intellij.scala"
export SCALA_PLUGIN_MD5="852d400070c4bee7b1d7e5385ada8a83"

export INTELLIJ_PLUGINS_HOME="$CWD/.cache/intellij/$FULL_IJ_BUILD_NUMBER/plugins"
export INTELLIJ_HOME="$CWD/.cache/intellij/$FULL_IJ_BUILD_NUMBER/idea-dist"
export OSS_PANTS_HOME="$CWD/.cache/pants"
export DUMMY_REPO_HOME="$CWD/.cache/dummy_repo"
export JDK_LIBS_HOME="$CWD/.cache/jdk-libs"

append_intellij_jvm_options() {
  scope=$1
  cmd=""

  INTELLIJ_JVM_OPTIONS=(
    "-Didea.load.plugins.id=com.intellij.properties,org.intellij.groovy,org.jetbrains.plugins.gradle,org.intellij.scala,PythonCore,JUnit,com.intellij.plugins.pants"
    "-Didea.plugins.path=$INTELLIJ_PLUGINS_HOME"
    "-Didea.home.path=$INTELLIJ_HOME"
    "-Dpants.plugin.base.path=$CWD/.pants.d/compile/jvm/java"
    "-Dpants.jps.plugin.classpath=$CWD/jps-plugin:$INTELLIJ_HOME/lib/jps-model.jar"
    #EAP build does not know its own build number, thus failing to tell plugin compatibility.
    "-Didea.plugins.compatible.build=$IJ_BUILD_NUMBER"
    # "-Dcompiler.process.debug.port=5006"
  )
  for jvm_option in ${INTELLIJ_JVM_OPTIONS[@]}
  do
      cmd="$cmd --jvm-$scope-options=$jvm_option"
  done
  while read jvm_option; do
    cmd="$cmd --jvm-$scope-options=$jvm_option"
  done < "${2:-"$CWD/resources/idea64.vmoptions"}"

  echo $cmd
}

export JDK_JARS="$(printf "%s\n" 'sa-jdi.jar' 'tools.jar')"

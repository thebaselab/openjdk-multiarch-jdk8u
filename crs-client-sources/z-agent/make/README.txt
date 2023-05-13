
Ways to build crs-agent and Zulu11:

1) build Zulu11 with agent for CRS-dev purpose
1.1)
    pushd ./ext
    git clone crs
    popd

    bash az/configure --with-crs
    make images

1.2)
    pushd cd $CRS_DIR
    git clone crs
    popd

    bash az/configure --with-crs=$CRS_DIR
    make images

2) Preparing directory for --with-import-modules by ./crs/z-agent.mk makefile:
2.1)
    pushd $CRS
    make -f $CRS/z-agent/make/z-agent.mk package JAVA_HOME=<with jfr patches> EXPORT_MODULES_DIR=$SOME_DIR
    popd

    bash az/configure --with-crs --with-import-modules=$SOME_DIR
    make images

2.2)
    pushd $CRS
    make -f $CRS/z-agent/make/z-agent.mk package JAVA_HOME=<with jfr patches> Z_AGENT_PRE_JMOD=$AGENT_M_ZIP
    popd

    bash az/configure --with-crs=$AGENT_M_ZIP
    make images

2.3)
    pushd $CRS
    make -f $CRS/z-agent/make/z-agent.mk package JAVA_HOME=<with jfr patches> Z_AGENT_PRE_JMOD=$AGENT_M_ZIP
    popd

    export PREBUILT_CRS_AGENT_URL=$AGENT_M_ZIP
    bash az/configure --with-crs
    make images

Note:
  * Using existing crs-agent.jar (build using crs-agent from Zulu8) prohibited.
  * --with-crs=<arg> may take optional argument leading to import-modules directory or zip file/url, or crs sources root
  * all az/configure <args> executions above may be replaced with:
  ```
      export CUSTOM_CONFIG_DIR="$TOPDIR/az/make/autoconf"
      configure <args>
  ```


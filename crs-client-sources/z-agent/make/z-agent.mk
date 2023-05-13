#!/usr/bin/make

SHELL := /bin/bash

BUILDTIME=$(shell date -u +"%Y-%m-%dT%H:%M:%SZ")

ifeq ($(JAVA_HOME),)
$(error "JAVA_HOME is unset")
endif

__JAVA_HOME := $(JAVA_HOME)

JAVA_VERSION:=$(shell $(__JAVA_HOME)/bin/java -version 2>&1 | head -n 1 | awk '{print $$3}' | sed 's/"//g' | sed 's/^\([0-9]*\.[0-9]\)\..*$$/\1/')

ZIP := zip

Z_AGENT_DIR=$(dir $(abspath $(lastword $(MAKEFILE_LIST))/../))

SRC_ROOT=$(dir $(abspath $(Z_AGENT_DIR)))

CRS_PRE_VERSION  = $(shell sed -n 's/.*<crs.stack.client.version>\(.*\)<\/crs.stack.client.version>/\1/p' $(SRC_ROOT)/client/pom.xml)
CRS_NIGHT_BUILD_VERSION = $(shell [[ -z "${CRS_BUILD_NUMBER}" ]] && echo "" || echo -${CRS_BUILD_NUMBER} )
CRS_VERSION = $(CRS_PRE_VERSION)$(CRS_NIGHT_BUILD_VERSION)
CRS_REVISION = $(shell git rev-parse --short=7 HEAD)
CRS_REVDIRTY = $(shell git describe --always --dirty=+ | fgrep -q + && echo yes)

#sanity check
ifneq ($(shell [[ "$(CRS_VERSION)" =~ ^[0-9]*\.[0-9]*\.[0-9]*.*$$ ]] && echo ok),ok)
$(error "Failed to parse crs.stack.client.version='$(CRS_VERSION)'")
endif

BUILD_DIR ?= $(Z_AGENT_DIR)/target/make

OUT_ROOT:=$(BUILD_DIR)

CLASSES=$(OUT_ROOT)/classes
BUILT_MODULE_DIR := $(OUT_ROOT)/target_classes
CLASS_PATH := .
EXPORT_MODULES_DIR ?= $(OUT_ROOT)/crs-agent-pre-jmod/
EXTRACT_JMOD_DIR := $(EXPORT_MODULES_DIR)/modules/com.azul.crs.client
Z_AGENT_JMOD ?= $(OUT_ROOT)/com.azul.crs.client.jmod
Z_AGENT_PRE_JMOD ?= $(OUT_ROOT)/crs-agent-pre-jmod.zip
Z_AGENT_JAR ?= $(OUT_ROOT)/crs-agent.jar

JAVAC8_BOOTCLASPATH_ARGS=\
	$(__JAVA_HOME)/jre/lib/rt.jar:$(__JAVA_HOME)/jre/lib/jfr.jar:$(__JAVA_HOME)/jre/lib/cat.jar:$(__JAVA_HOME)/jre/lib/jce.jar

JAVAC8_ARGS=

JAVAC11_ARGS=\
	--add-exports java.base/sun.security.validator=ALL-UNNAMED \
	--add-exports java.base/sun.launcher=ALL-UNNAMED \
	--add-exports jdk.jfr/jdk.jfr.internal=ALL-UNNAMED \
	--add-exports java.base/sun.net.dns=ALL-UNNAMED \
	--add-exports java.base/sun.net.www.protocol.jar=ALL-UNNAMED \
	--add-exports java.base/jdk.internal.loader=ALL-UNNAMED \
	--add-exports java.base/com.azul.tooling.in=ALL-UNNAMED \
	--add-exports com.azul.tooling/com.azul.tooling=ALL-UNNAMED \
	--add-exports java.base/sun.net.www.protocol.http=ALL-UNNAMED \
	--add-exports java.base/sun.net.www.protocol.https=ALL-UNNAMED \
	--add-exports java.base/sun.net.www.http=ALL-UNNAMED \
	--add-opens java.base/sun.net.www.protocol.http=ALL-UNNAMED \
	--add-opens java.base/sun.net.www.protocol.https=ALL-UNNAMED \
	--add-opens java.base/sun.net.www.http=ALL-UNNAMED \
	#

REMOVE_BEFORE_PACKAGE=\
	com/azul/crs/json/buildtime \
	com/azul/crs/json/JsonProperty \
	com/azul/crs/json/CompileJsonSerializer \
	com/azul/crs/util/logging/annotations \
	META-INF/services/javax.annotation.processing.Processor \
	\.java$

ifeq ($(JAVA_VERSION),1.8)
# Assuming 1.8.0
JAVAC_ARGS := $(JAVAC8_ARGS)
PACKAGE := $(Z_AGENT_JAR)
RESULTING_FILE := $(Z_AGENT_JAR)
MANIFEST_FILE := $(CLASSES)/META-INF/MANIFEST.MF
else
# Probably 11.0.1+
JAVAC_ARGS := $(JAVAC11_ARGS)
PACKAGE := pre_jmod
RESULTING_FILE := $(Z_AGENT_PRE_JMOD)
STRIP_JIMAGE_DEPS := strip_jimage
# in 11+ MANIFEST.MF used only to get some build properties
# moving to package since accessing by getResource("META-INF/MANIFEST.MF") may return
# our manifest
MANIFEST_FILE := $(CLASSES)/META-INF/com.azul.crs/MANIFEST.MF

OLD_JAVA_HOME := $(__JAVA_HOME)
NEW_JAVA_HOME := $(OUT_ROOT)/jre-to-use
__JAVA_HOME := $(NEW_JAVA_HOME)
JMOD=$(__JAVA_HOME)/bin/jmod
endif

JAVAC=$(__JAVA_HOME)/bin/javac
JAVA=$(__JAVA_HOME)/bin/java
JAR=$(__JAVA_HOME)/bin/jar

default: all

$(MANIFEST_FILE): $(CLASSES)
	mkdir -p `dirname "$@"`
	truncate -s 0 "$@"
	echo "Manifest-Version: 1.0" >> "$@"
	echo "Premain-Class: com.azul.crs.client.Agent001" >> "$@"
	echo "Build-Number: " >> "$@"
	echo "Build-Timestamp: $(BUILDTIME)" >> "$@"
	echo "Implementation-Version: $(CRS_VERSION)" >> "$@"
	echo "Build-Revision: $(shell git log -1 --format="%H")" >> "$@"
	echo "Build-OS: $(shell uname -s) $(shell uname -m) $(shell uname -r)" >> "$@"
	echo "Build-Jdk-Spec: $(JAVA_VERSION)" >> "$@"
	echo "Created-By: z-agent.mk" >> "$@"
	echo "Build-Jdk: $(shell $(__JAVA_HOME)/bin/java -version 2>&1 | head -n 1 |  sed -e 's/"//g' -e 's/^openjdk\s*version//i')" >> "$@"
	echo "" >> "$@"

TARGETS += $(MANIFEST_FILE)

# for some reason --patch-module com.azul.crs.client=./classes doesn't help to fight visibility issues between
# crs module and LoggingAnnotationProcessor, so preparing here jre image without crs to be used as build_Jdk
strip_jimage:
	rm -rf $(NEW_JAVA_HOME)
	$(OLD_JAVA_HOME)/bin/jlink --output $(NEW_JAVA_HOME) --add-modules java.base,java.instrument,jdk.jfr,java.compiler,jdk.compiler,java.naming,java.management,jdk.jlink,com.azul.tooling
	test -e $(JAVAC)

.PHONY: jimage

$(CLASSES): clean
	mkdir -p $(CLASSES)

CLIENT: JSON_TOOL CRS_LOG JSON_GENERATED
Z_AGENT: JAR_TOOL CLIENT CRS_LOG JSON_TOOL

# Helper function to compile maven modules required for z-agent
#
# $1 - target name
# $2 - directory (from root of project) of mavent module to compile
# $3 - optional annotation processor class
# $4 - optional annotation processor class
#
define build_module
$1_target := $1
$1_classes := $(BUILT_MODULE_DIR)/$1_classes
$1_files_dir := $(SRC_ROOT)/$2/src/main/java
$1_resources_dir := $(SRC_ROOT)/$2/src/main/resources
$1_files := $$(shell test -d $$($1_files_dir) && cd $$($1_files_dir) && find ./ -type f -name '*.java' || true)
$1_resources := $$(shell test -d $$($1_resources_dir) && cd $$($1_resources_dir) && find ./ -type f || true)

CLASS_PATH := $(CLASS_PATH):$$($1_classes)

ifneq ($3,)
$1_processor_args := $3
endif

ifneq ($4,)
$1_processor_args := $$($1_processor_args),$4
endif

ifneq ($3$4,)
$1_processor_args := -processorpath $$(CLASS_PATH) -processor $$($1_processor_args)
endif

ifeq ($(JAVA_VERSION),1.8)
$1_bootclasspath_args := -bootclasspath $$(JAVAC8_BOOTCLASPATH_ARGS):$$(CLASS_PATH)
endif

$$($1_target): $$(CLASSES) $$(patsubst %,$$($1_files_dir)/%,$$($1_files)) $$(patsubst %,$$($1_resources_dir)/%,$$($1_resources)) $$(STRIP_JIMAGE_DEPS)
	echo "Compile $2"
	mkdir -p $$($1_classes)
	cd $$($1_files_dir) && $$(JAVAC) $$(JAVAC_ARGS) $$($1_bootclasspath_args) -cp $$(CLASS_PATH) -d $$($1_classes) $$($1_processor_args) $$($1_files)
	if test -d $$($1_resources_dir) ; then cp -r $$($1_resources_dir)/* $$($1_classes) ; fi
	cd $$($1_classes) && for f in `find . -type f` ; do \
		remove=0; \
		for m in $(REMOVE_BEFORE_PACKAGE) ; do \
			if [[ "$$$$f" =~ "$$$$m" ]] ; then remove=1; fi ; \
		done; \
		if [ $$$$remove -eq 0 ]; then \
		  mkdir -p $$(CLASSES)/`dirname "$$$$f"` || exit 1; \
			if [[ "$$$$f" =~ 'crslog.channels.cfg' ]]; then \
				cat "$$$$f" >> "$$(CLASSES)/$$$$f" || exit 1; \
			elif [[ "$$$$f" =~ 'version.properties' ]] ; then \
				cat "$$$$f" | sed -e 's/$$$${crs.stack.client.version}/$(CRS_VERSION)/g' \
                                                  -e 's/$$$${crs.stack.client.revision}/$(CRS_REVISION)/g' \
                                                  -e 's/$$$${crs.stack.client.revisionIsDirty}/$(CRS_REVDIRTY)/g' \
                                                  -e 's/$$$${crs.build.timestamp}/$(BUILDTIME)/g' > "$$(CLASSES)/$$$$f" || exit 1; \
			else \
				cp "$$$$f" "$$(CLASSES)/$$$$f" || exit 1; \
			fi; \
		fi; \
	done


TARGETS += $$($1_target)
endef

MODULES_RECIPES := \
		JSON_GENERATED,json-generated \
		JSON_TOOL,json-tool \
		CRS_LOG,crs-log \
		CRS_UTILS,crs-utils \
		CLIENT,client,com.azul.crs.json.buildtime.JSONAnnotaitonProcessor,com.azul.crs.util.logging.annotations.LoggingAnnotationProcessor \
		JAR_TOOL,jar-tool,com.azul.crs.json.buildtime.JSONAnnotaitonProcessor,com.azul.crs.util.logging.annotations.LoggingAnnotationProcessor \
		Z_AGENT,z-agent,com.azul.crs.util.logging.annotations.LoggingAnnotationProcessor

$(foreach module_recipe,$(MODULES_RECIPES),$(eval $$(eval $$(call build_module,$(module_recipe)))))

all: $(TARGETS)

# Java11 targets

ifneq ($(JAVA_VERSION),1.8)

module_info: $(CLASSES) strip_jimage
	$(JAVAC) $(JAVAC_ARGS) -d $(CLASSES) $(Z_AGENT_DIR)/make/modules_src/com.azul.crs.client/module-info.java

$(Z_AGENT_JMOD): module_info $(TARGETS)
	if test -e $@; then rm $@ ; fi
	$(JMOD) create --class-path $(CLASSES) $@

extract_jmod: $(Z_AGENT_JMOD)
	rm -rf $(EXTRACT_JMOD_DIR)
	mkdir -p $(EXTRACT_JMOD_DIR)
	$(JMOD) extract --dir $(EXTRACT_JMOD_DIR) $(Z_AGENT_JMOD)
	mv $(EXTRACT_JMOD_DIR)/classes/* $(EXTRACT_JMOD_DIR)/
	rmdir $(EXTRACT_JMOD_DIR)/classes/

copy_extras:
	cp -r $(Z_AGENT_DIR)/make/modules_src $(EXPORT_MODULES_DIR)/
	cp -r $(Z_AGENT_DIR)/make/modules_conf $(EXPORT_MODULES_DIR)/
	cp -r $(Z_AGENT_DIR)/make/make $(EXPORT_MODULES_DIR)/

pre_jmod: extract_jmod copy_extras
	cd $(EXPORT_MODULES_DIR) && $(ZIP) -r $(Z_AGENT_PRE_JMOD) ./*

endif

$(Z_AGENT_JAR): $(TARGETS)
	cd $(CLASSES) && $(JAR) cfm $(Z_AGENT_JAR) $(MANIFEST_FILE) ./

print_src_dirs:
	@echo $(MODULES_RECIPES) | xargs -n 1 -- echo | sed -e 's/^[^,]*,//' | sed -e 's/,.*$$//g'

package: $(PACKAGE)
	echo -e "\n\n  *** packaging is done. see $(RESULTING_FILE)"

clean:
	rm -rf $(CLASSES)

.PHONY: all jmod jar clean print_src_dirs package copy_extras extract_jmod module_info  print_src_dirs pre_jmod default


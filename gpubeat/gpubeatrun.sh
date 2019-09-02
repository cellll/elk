#!/bin/bash
$RUNPATH/nvidiagpubeat -c $RUNPATH/nvidiagpubeat.yml -e -d "*" -E seccomp.enabled=false -E output.elasticsearch.hosts=["$eshost:9200"]

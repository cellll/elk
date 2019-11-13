#!/bin/bash

# ESHOST=$1
ESHOST='localhost'
ESPORT='9200'

#----------------- DELETE default policies/templates -----------------

# DELETE default METRICBEAT ILM policy 
curl -X DELETE "$ESHOST:$ESPORT/_ilm/policy/metricbeat-7.3.1?pretty"

# DELETE default METRICBEAT Template
curl -X DELETE "$ESHOST:$ESPORT/_template/metricbeat-*?pretty"

# DELETE default NVIDIAGPUBEAT ILM policy 
curl -X DELETE "$ESHOST:$ESPORT/_ilm/policy/nvidiagpubeat-7.3.2?pretty"

# DELETE default NVIDIAGPUBEAT Template
curl -X DELETE "$ESHOST:$ESPORT/_template/nvidiagpubeat-*?pretty"


#----------------- PUT custom policies/templates -----------------

# PUT custom METRICBEAT ILM Policy
curl -X PUT "$ESHOST:$ESPORT/_ilm/policy/metricbeat?pretty" -H 'Content-Type: application/json' -d'
{
  "policy": {                       
    "phases": {
      "hot": {
        "min_age" : "0ms",                      
        "actions": {
          "rollover": {             
            "max_size": "10GB",
            "max_age": "1d"
          }
        }
      }
    }
  }
}
'

# PUT custom METRICBEAT Template

curl -X PUT "$ESHOST:$ESPORT/_template/metricbeat?pretty" -H 'Content-Type: application/json' -d'
{
  "index_patterns": ["metricbeat-*"],                 
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 1,
    "index.lifecycle.name": "metricbeat",      
    "index.lifecycle.rollover_alias": "metricbeat"    
  }
}
'


# PUT custom NVIDIAGPUBEAT ILM Policy
curl -X PUT "$ESHOST:$ESPORT/_ilm/policy/nvidiagpubeat?pretty" -H 'Content-Type: application/json' -d'
{
  "policy": {                       
    "phases": {
      "hot": {
        "min_age" : "0ms",                      
        "actions": {
          "rollover": {             
            "max_size": "10GB",
            "max_age": "1d"
          }
        }
      }
    }
  }
}
'

# PUT custom NVIDIAGPUBEAT Template
curl -X PUT "$ESHOST:$ESPORT/_template/nvidiagpubeat?pretty" -H 'Content-Type: application/json' -d'
{
  "index_patterns": ["nvidiagpubeat-*"],                 
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 1,
    "index.lifecycle.name": "nvidiagpubeat",      
    "index.lifecycle.rollover_alias": "nvidiagpubeat"    
  }
}
'

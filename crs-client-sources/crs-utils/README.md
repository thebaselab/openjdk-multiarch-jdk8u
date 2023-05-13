# CRS Utils
CRS library with generic utils required by client, cloud and testing components of CRS service.
The library is assembled into CRS agent and is the part of Java runtimes delivered by Azul. 
MUST have NO or minimal dependencies to not introduce any license issue into Azul products.

## Deploy to Nexus

You need to set up OS environment variables ```USERNAME_NEXUS``` and ```PASSWORD_NEXUS``` (ask devops if you need credentials).
Use following command from the root directory of the project:
```mvn clean deploy --projects 'crs-utils' -am -Denforcer.skip=true -P nexus -s settings.xml```

You can find your artifact at the Nexus here:
https://nexus.azulsystems.com/service/rest/repository/browse/crs-mixed/com/azul/crs/crs-utils/ 
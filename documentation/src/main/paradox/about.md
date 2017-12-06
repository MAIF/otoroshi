# About Otoroshi

At the beginning of the 2016, we had the need to create a new environment to be able to create new "digital" products very quickly in an agile fashion at <a href="https://www.maif.fr/" target="_blank">MAIF</a>. Naturally we turned to PaaS solutions and chose the excellent <a href="https://www.clever-cloud.com/">Clever-Cloud</a> product run our apps. 

We also chose that every feature team will the freedom to choose its own stack to build its product. It was a nice move but it has also introduced some challenges in terms of homogeneity for traceability, security, logging, ... because we did not want to force library usage in the products. We could have used something like <a href="http://philcalcado.com/2017/08/03/pattern_service_mesh.html" target="_blank">Service Mesh Pattern</a> but the model of deployement in <a href="https://www.clever-cloud.com/">Clever-Cloud</a> prevented us to do it.

The solution was to use a reverse proxy or some kind of API Gateway able to provide tracability, logging, security with apikeys, quotas, DNS as a service locator, etc. We needed something easy to use, with a human friendly UI, a nice API to extends its features, true hot reconfiguration, able to generate internal events for third party usage. Many solutions where available at that time, but none seems to fit our needs, there was always something missing, too complicated for our needs or not playing well with <a href="https://www.clever-cloud.com/">Clever-Cloud</a> depoyment model.

At some point, we tried to write a small prototype to explore what could be our dream reverse proxy. The design was very simple but every major feature needed was there. 

**Otoroshi** was born and we decided to move ahead with our heairy monster :)
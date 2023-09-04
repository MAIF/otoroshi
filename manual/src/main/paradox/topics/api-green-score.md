# API Green Score

The API green score is a label created by the [API Thinking](https://www.collectif-api-thinking.com) collective. The main objective is to advise users on the consumption of their APIs.

Otoroshi has a complete integration of the all collective rules, divided into four concerns: **Architecture**, **Design**, **Usage** and **Logs retention**. The 6000 score points are spread over the four parts and a final note is given for each group of routes.

The API green score is available on 16.8.0 or later version of Otoroshi. You can find the feature on the search bar of your Otoroshi UI or directly in the sidebar by clicking on **Green scores**.

To start the process, click on Add Item, give a name to the group and select a first route to audit. After clicking on the hammer icon, you can select the rules respected by your route. Before saving, you can adjust the three dynamic thresholds. These thresholds are used to calculate a second green score depending on the number of plugins that you allowed on your route and the amount of data you want not to exceed from your downstream service.


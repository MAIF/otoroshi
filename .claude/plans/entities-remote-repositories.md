# Otoroshi remote catalogs

## Content

le but ici c'est d'implémenter une nouvelle extension dans otoroshi. Cette extension sera disponible directement dans le core otoroshi.
En gros l'idée c'est d'avoir un moyen de synchroniser des entités otoroshi avec un catalogue distant en lecture seulement. C'est un peu comme un git pull mais sans possibilité de push. C'est grossièrement de l'infra as code ou otoroshi a la résponsabilité de récupérer des entités distantes et de les déployer dans otoroshi.

Le truc serait pluggable et permettrait d'avoir plusieurs types de sources de données, comme git, github, gitlab, s3, http, etc. 

Le code doit se situer dans le dossier otoroshi/app/next/catalogs avec au moins les éléments suivant

- api.scala
- extension.scala
- model.scala
- plugins.scala

exemple d'extension:

- https://raw.githubusercontent.com/MAIF/otoroshi/refs/heads/master/otoroshi/app/next/workflow/extension.scala
- https://raw.githubusercontent.com/cloud-apim/otoroshi-waf-extension/refs/heads/main/src/main/scala/com/cloud/apim/otoroshi/extensions/waf/extension.scala

exemple de projet d'extension:

- https://github.com/cloud-apim/otoroshi-waf-extension/tree/main

## Entité remote catalog

je vois bien l'entité remote catalog comme ca

```json
{
  "id": "remote-catalog_xxxxx",
  "name": "My Remote Catalog",
  "description": "My Remote Catalog for github project otoroshi-ee/otoroshi",
  "tags": [],
  "metadata": {},
  "enabled": true,
  "source_kind": "github",
  "source_config": {
    "repo": "https://github.com/otoroshi-ee/otoroshi.git",
    "branch": "main",
    "path": "/path/to/file",
    "token": "xxxxxx"
  },
  "scheduling": {
    "enabled": false,
    "kind": "ScheduledEvery",
    "instantiation": "OneInstancePerOtoroshiInstance",
    "initial_delay": 1000,
    "interval": 60000,
    "cron_expression": "",
    "deploy_args": {
      "foo": "bar"
    }
  },
  "test_deploy_args": {
    "foo": "bar"
  }
}
```

exemple d'entité:

- https://raw.githubusercontent.com/MAIF/otoroshi/refs/heads/master/otoroshi/app/next/workflow/extension.scala
- https://raw.githubusercontent.com/cloud-apim/otoroshi-waf-extension/refs/heads/main/src/main/scala/com/cloud/apim/otoroshi/extensions/waf/entities/wafconfig.scala

## Réconciliation

il faudrait avoir une réconciliation basée sur une metadonnée. Par exemple, on pourrait avoir une metadonnée "created_by: remote_catalog=<remote_catalog_id>" sur chaque entité. le flow serait le suivant

- déclenchement du deploy
- récupération des entités distantes
- récupération des entités locales avec created_by: remote_catalog=<remote_catalog_id>
- upsert des entités distantes
- remove des entités locales avec created_by: remote_catalog=<remote_catalog_id> n'apparaissant plus dans la liste des entités distantes

## Scheduling

il faudrait avoir dans l'extension une methode qui lance des job si besoin en se basant sur la config de scheduling, dans le meme genre que ce que fait l'extension de workflow.

```scala
private[workflow] val handledJobs = new UnboundedTrieMap[String, Job]()
...

override def syncStates(): Future[Unit] = {
  implicit val ec = env.otoroshiExecutionContext
  implicit val ev = env
  for {
    configs <- datastores.remoteCatalogsDatastore.findAllAndFillSecrets()
  } yield {
    states.updateRemoteCatalogs(configs)
    startJobsIfNeeded(configs)
    ()
  }
}

...

def startJobsIfNeeded(remoteCatalogs: Seq[RemoteCatalog]): Unit = {
  val currentIds: Seq[String] = remoteCatalogs.filter(_.scheduling.enabled).map { remoteCatalog =>
    val actualJob        = new RemoteCatalogJob(remoteCatalog.id, remoteCatalog.scheduling)
    val uniqueId: String = actualJob.uniqueId.id
    if (!handledJobs.contains(uniqueId)) {
      handledJobs.put(uniqueId, actualJob)
      env.jobManager.registerJob(actualJob)
    }
    uniqueId
  }
  handledJobs.values.toSeq.foreach { job =>
    val id: String = job.uniqueId.id
    if (!currentIds.contains(id)) {
      handledJobs.remove(id)
      env.jobManager.unregisterJob(job)
    }
  }
}
```

exemple d'implémentation:

- https://raw.githubusercontent.com/MAIF/otoroshi/refs/heads/master/otoroshi/app/next/workflow/job.scala

## UI

il faudrait avoir une page dans le backoffice permettant de lister les remote catalogs et de les modifier. mais c'est classique dans une extension. Il faudra juste avoir la possibilité de lancer un deploy manuellement depuis un remote catalog.

Il serait également interessant d'avoir un bouton "test" permettant de tester le deploy manuellement mais en mode dry run, ca renvoit une sorte de rapport avec la liste des entités récupérées et le rapport de reconciliation.

exemple d'UI

- https://raw.githubusercontent.com/cloud-apim/otoroshi-waf-extension/refs/heads/main/src/main/resources/cloudapim/extensions/waf/WafConfigsPage.js

## Types de sources

je vois bien l'api d'une source comme ca

```scala
case class RemoteEntity(id: String, kind: String, source: String, syncAt: DateTime, content: JsObject)

trait CatalogSource {
  def sourceKind: String
  def supportsWebhook: Boolean
  def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue): Future[Either[JsValue, Seq[RemoteCatalog]]]
  def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue): Future[Either[JsValue, JsObject]]
  def fetch(catalog: RemoteCatalog, args: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Seq[RemoteEntity]]]
  def deploy(catalog: RemoteCatalog, args: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]]
}
```

il faudrait un mécanisme pluggable pour les sources. C'est un peu comme le mécanisme de workflow. On a une map dans un object qui contient les type possibles

```scala
object CatalogSources {
  private val possibleSources: TrieMap[String, CatalogSource] = {
    val m = new TrieMap[String, CatalogSource]()
    m.addAll(
      "file" -> new CatalogSourceFile(),
      "github" -> new CatalogSourceGithub(),
      "gitlab" -> new CatalogSourceGitlab(),
      "s3" -> new CatalogSourceS3(),
      "http" -> new CatalogSourceHttp(),
    )
    m
  }
  def registerSource(name: String, source: CatalogSource): Unit = {
    possibleSources.put(name, source)
  }
}
```

## Admin API

il faudrait avoir une route de l'api d'admin otoroshi permettant de deploy une liste de catalogues via un post. Le body pourrait ressembler à ca

```json
[
  {
    "id": "remote_catalog_xxxxx",
    "args": {
      "foo": "bar"
    }
  },
  {
    "id": "remote_catalog_yyyyy",
    "args": {
      "foo": "qix"
    }
  }
]
```

## Plugins

Nous avons besoin de 3 plugins:

- deploy single: un plugin type backend call qui permet de deploy un catalogue via un post, avec préselection du catalogue dans la config du plugin. possibilité de passer des args depuis le body du post
- deploy many: un plugin type backend call qui permet de deploy plusieurs catalogues via un post, avec préselection des catalogues dans la config du plugin. possibilité de passer des args depuis le body du post
- deploy webhook: un plugin type webhook qui permet de deploy des catalogues via un post, avec préselection des catalogues dans la config du plugin. Ici, il y aura également la possibilité de selectionner le type de body qui arrivera (webhook gitlab, webhook github, etc)

exemple de plugins:

- https://github.com/cloud-apim/otoroshi-waf-extension/blob/main/src/main/scala/com/cloud/apim/otoroshi/extensions/waf/plugins/waf.scala
- https://github.com/MAIF/otoroshi/blob/master/otoroshi/app/next/workflow/plugins.scala


## TODO

- [ ] extension entities remote catalog
- [ ] entité remote catalog
- [ ] job permettant de fetch les catalogues basé sur les cron expressions de chaque catalogue (si activé)
- [ ] UI for remote catalogues
- [ ] core logic 
  - [ ] fetch
  - [ ] deploy
  - [ ] reconciliation basée sur une metadonnée
  - [ ] api de entities_source pluggable
- [ ] implémentations de entities_source
  - [ ] entities_source_file
  - [ ] entities_source_s3  
  - [ ] entities_source_git
  - [ ] entities_source_github
  - [ ] entities_source_gitlab
  - [ ] entities_source_http
- [ ] routes backoffice
  - [ ] fetch + rapport, en mode dry run
  - [ ] deploy
- [ ] routes api
  - [ ] deploy
- [ ] plugins
  - [ ] deploy single 
  - [ ] deploy many (based on body content)
  - [ ] deploy webhook (based on source specific webhook + selected catalogues)


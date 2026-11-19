package otoroshi.next.services

import otoroshi.next.models.{Api, ApiPlan, ApiSubscription}
import otoroshi.api.WriteAction.Update
import otoroshi.env.Env
import otoroshi.models.Draft
import otoroshi.storage.BasicStore
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSeqOfA, BetterSyntax}
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

object ApiConsistencyService {

  private val logger = Logger("otoroshi-api-consistency-service")

  def applyApiChanges(oldApi: Api, newApi: Api, isDraft: Boolean)(using env: Env): Future[Api] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    val oldPlans = oldApi.plans
    val newPlans = newApi.plans

    val oldById = oldPlans.map(p => p.id -> p).toMap
    val newById = newPlans.map(p => p.id -> p).toMap

    val apiHasChanged = oldApi != newApi

    // println(("api has changed", apiHasChanged))

    if (apiHasChanged) {
      val deletedPlans = oldById.keySet.diff(newById.keySet).map(oldById)

//      val createdPlans = newById.keySet.diff(oldById.keySet).map(newById)

      val updatedPlans = oldById.keySet
        .intersect(newById.keySet)
        .flatMap { id =>
          val oldP = oldById(id)
          val newP = newById(id)
          if (oldP != newP) Some(newP) else None
        }

      for {
        _ <- Future.sequence(deletedPlans.map(plan => deleteSubscriptionsByPlan(newApi, plan, isDraft)))
        _ <- Future.sequence(updatedPlans.map(plan => updateSubscriptionsByPlan(newApi, plan, isDraft)))
        _ <- if (isDraft) Future.unit else updateMintedConsumersByPlan(newApi, oldById, updatedPlans.toSeq)
      } yield newApi
    } else {
      newApi.vfuture
    }
  }

  // the restrictions and the rate limiting of a keyless, jwt, mtls or oauth2-remote plan belong to the plan: the keys its
  // extractor minted for its consumers, which have no other source for them, follow their changes. Everything else of
  // these keys (secret, enabled flag, validity, tags, metadata, counters) is kept. The keys provisioned by the
  // subscriptions of the plan are rewritten with them by updateSubscriptionsByPlan
  private def updateMintedConsumersByPlan(api: Api, oldById: Map[String, ApiPlan], updatedPlans: Seq[ApiPlan])(using
      env: Env,
      ec: ExecutionContext
  ): Future[Unit] = {
    val changed = updatedPlans.filter { plan =>
      ApiPlan.consumerClientIdKind(plan.accessModeConfigurationType).isDefined &&
      oldById.get(plan.id).exists { old =>
        old.apikeyTemplate.restrictions != plan.apikeyTemplate.restrictions || old.rateLimiting != plan.rateLimiting
      }
    }
    if (changed.isEmpty) Future.unit
    else {
      env.datastores.apiKeyDataStore.findAll().flatMap { keys =>
        changed
          .flatMap { plan =>
            val ref = otoroshi.next.models.ApiRef(api.id, plan.id, "")
            keys
              .filter(key => key.metadata.get("created_by").contains("apikey-extractor") && key.apiRef.contains(ref))
              .map(key =>
                key.copy(restrictions = plan.apikeyTemplate.restrictions, throttlingStrategy = plan.rateLimiting)
              )
          }
          .mapAsync(_.save())
          .map(_ => ())
      }
    }
  }

  def deleteSubscriptionsByPlan(api: Api, plan: ApiPlan, isDraft: Boolean)(using
                                                                           env: Env,
                                                                           ec: ExecutionContext
  ): Future[Api] = {
    // println(("delete plan", plan.name))

    (if (isDraft)
       batchSubscriptionsUpdates[Draft](
         plan,
         api,
         Some(subs => env.datastores.draftsDataStore.deleteByIds(subs.map(_.id)))
       )
     else
       batchSubscriptionsUpdates[ApiSubscription](
         plan,
         api,
         fnDraft = None,
         Some(subs => deleteSubscriptionsAndKeys(api, plan, subs))
       ))
      .map(_ => api)
  }

  // the api keys of the subscriptions go with them, deleted or disabled as when a subscription is deleted. A
  // subscription whose keys could not be revoked is kept, and the write of the api fails, so that the plan stays in
  // the api and the next write of the api retries it
  private def deleteSubscriptionsAndKeys(api: Api, plan: ApiPlan, subs: Seq[ApiSubscription])(using
      env: Env,
      ec: ExecutionContext
  ): Future[Unit] = {
    subs.mapAsync(sub => ApiSubscription.revokeKeys(sub).map(result => (sub, result))).flatMap { results =>
      val (deletable, kept) = results.partition(_._2.isRight)
      val deleted           =
        if (deletable.isEmpty) ().vfuture
        else env.datastores.apiSubscriptionDataStore.deleteByIds(deletable.map(_._1.id)).map(_ => ())
      deleted.flatMap { _ =>
        if (kept.isEmpty) ().vfuture
        else {
          val ids = kept.map(_._1.id).mkString(", ")
          logger.error(
            s"the api keys of the subscriptions $ids to the plan '${plan.id}' removed from api '${api.id}' could not be revoked, the subscriptions are kept"
          )
          Future.failed(
            new RuntimeException(
              s"the api keys of the subscriptions $ids to the plan '${plan.id}' could not be revoked, retry the write of the api"
            )
          )
        }
      }
    }
  }

  private def _batchSubscriptionsUpdates[A](
                                             page: Int,
                                             plan: ApiPlan,
                                             api: Api,
                                             fn: Seq[A] => Future[Any],
                                             store: BasicStore[A]
  )(using env: Env, ec: ExecutionContext): Future[Unit] = {

    val pageSize = 50

    store
      .streamedFindAndMat(
        // two apis can give the same id to their plans
        {
          case draft: Draft         =>
            draft.content.selectAsOptString("plan_ref").getOrElse("") == plan.id &&
            draft.content.selectAsOptString("api_ref").getOrElse("") == api.id
          case sub: ApiSubscription => sub.planRef == plan.id && sub.apiRef == api.id
        },
        fetchSize = pageSize,
        page = page
      )(using ec, env.otoroshiMaterializer, env)
      .flatMap { subscriptions =>
        if (subscriptions.isEmpty) {
          Future.unit
        } else {
          for {
            _ <- fn(subscriptions)
            _ <- if (subscriptions.size < pageSize) {
                   Future.unit
                 } else {
                   _batchSubscriptionsUpdates(page + 1, plan, api, fn, store)
                 }
          } yield ()
        }
      }
  }

  private def batchSubscriptionsUpdates[A](
                                            plan: ApiPlan,
                                            api: Api,
                                            fnDraft: Option[Seq[Draft] => Future[Any]],
                                            fnSub: Option[Seq[ApiSubscription] => Future[Any]] = None
  )(using env: Env, ec: ExecutionContext): Future[Unit] = {
    fnDraft match {
      case Some(fn) => _batchSubscriptionsUpdates[Draft](0, plan, api, fn, env.datastores.draftsDataStore)
      case None     =>
        _batchSubscriptionsUpdates[ApiSubscription](
          0,
          plan,
          api,
          fnSub.get,
          env.datastores.apiSubscriptionDataStore
        )
    }
  }

  def updateSubscriptionsByPlan(api: Api, plan: ApiPlan, isDraft: Boolean)(using
                                                                           env: Env,
                                                                           ec: ExecutionContext
  ): Future[Api] = {
    // println(("update plan", plan.name))

    if (isDraft) {
      batchSubscriptionsUpdates[Draft](
        plan,
        api,
        Some(subscriptions =>
          Future.traverse(subscriptions) { subscription =>
            ApiSubscription.handleSubscriptionChanged(
              api,
              plan,
              ApiSubscription.format.reads(subscription.content).get,
              Update,
              isDraft = true
            )
          }
        )
      )
        .map(_ => api)
    } else
      batchSubscriptionsUpdates[ApiSubscription](
        plan,
        api,
        None,
        Some(subscriptions =>
          Future.traverse(subscriptions) { subscription =>
            ApiSubscription.handleSubscriptionChanged(api, plan, subscription, Update, isDraft = false)
          }
        )
      )
        .map(_ => api)
  }
}

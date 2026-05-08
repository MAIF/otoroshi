package otoroshi.next.services

import next.models.{Api, ApiDocumentationPlan, ApiSubscription}
import otoroshi.api.WriteAction.Update
import otoroshi.env.Env
import otoroshi.models.Draft
import otoroshi.storage.BasicStore
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}

import scala.concurrent.{ExecutionContext, Future}

object ApiConsistencyService {

  def applyApiChanges(oldApi: Api, newApi: Api, isDraft: Boolean)(implicit env: Env): Future[Api] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    val oldPlans = oldApi.plans
    val newPlans = newApi.plans

    val oldById = oldPlans.map(p => p.id -> p).toMap
    val newById = newPlans.map(p => p.id -> p).toMap

    val apiHasChanged = oldApi != newApi

    println("api has changed", apiHasChanged)

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
      } yield ()

      newApi.vfuture
    } else {
      newApi.vfuture
    }
  }

  def deleteSubscriptionsByPlan(api: Api, plan: ApiDocumentationPlan, isDraft: Boolean)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Api] = {
    println("delete plan", plan.name)

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
         Some(subs => env.datastores.apiDataStore.deleteByIds(subs.map(_.id)))
       ))
      .map(_ => api)
  }

  private def _batchSubscriptionsUpdates[A](
      page: Int,
      plan: ApiDocumentationPlan,
      api: Api,
      fn: Seq[A] => Future[Any],
      store: BasicStore[A]
  )(implicit env: Env, ec: ExecutionContext): Future[Unit] = {

    val pageSize = 50

    store
      .streamedFindAndMat(
        {
          case draft: Draft         => draft.content.selectAsOptString("path_ref").getOrElse("") == plan.id
          case sub: ApiSubscription => sub.planRef == plan.id
        },
        fetchSize = pageSize,
        page = page
      )(ec, env.otoroshiMaterializer, env)
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
      plan: ApiDocumentationPlan,
      api: Api,
      fnDraft: Option[Seq[Draft] => Future[Any]],
      fnSub: Option[Seq[ApiSubscription] => Future[Any]] = None
  )(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
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

  def updateSubscriptionsByPlan(api: Api, plan: ApiDocumentationPlan, isDraft: Boolean)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Api] = {
    println("update plan", plan.name)

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

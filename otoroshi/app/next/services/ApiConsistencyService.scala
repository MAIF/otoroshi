package otoroshi.next.services

import next.models.{Api, ApiDocumentationPlan, ApiSubscription}
import otoroshi.api.WriteAction.Update
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.BetterSyntax

import scala.annotation.tailrec
import scala.concurrent.Future

trait PlanChangeStatus

object PlanChangeStatus {
  case object Create extends PlanChangeStatus
  case object Update extends PlanChangeStatus
  case object Delete extends PlanChangeStatus
}

object ApiConsistencyService {

  def applyApiChanges(oldApi: Api, newApi: Api)(implicit env: Env): Future[Api] = {
    val oldPlans = oldApi.plans
    val newPlans = newApi.plans

    val oldById = oldPlans.map(p => p.id -> p).toMap
    val newById = newPlans.map(p => p.id -> p).toMap

    val apiHasChanged = oldApi != newApi

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
        _ <- deletedPlans.map(plan => deleteSubscriptionsByPlan(newApi, plan))
        _ <- updatedPlans.map(plan => updateSubscriptionsByPlan(newApi, plan))
      } yield ()

      newApi.vfuture
    } else {
      newApi.vfuture
    }
  }

  private def batchSubscriptionsDeletion(page: Int, plan: ApiDocumentationPlan)(implicit env: Env): Future[Unit] = {
    implicit val ec = env.otoroshiExecutionContext

    env.datastores.apiSubscriptionDataStore
      .streamedFindAndMat(_.planRef == plan.id, fetchSize = 50, page = page)(ec, env.otoroshiMaterializer, env)
      .flatMap { subscriptions =>
        if (subscriptions.isEmpty) {
          Future.successful(())
        } else {
          for {
            _ <- env.datastores.apiDataStore.deleteByIds(subscriptions.map(_.id))
            _ <- if (subscriptions.size < 50) {
                   Future.successful(())
                 } else {
                   batchSubscriptionsDeletion(page + 1, plan)
                 }
          } yield ()
        }
      }
  }

  def deleteSubscriptionsByPlan(api: Api, plan: ApiDocumentationPlan)(implicit env: Env): Future[Api] = {
    implicit val ec = env.otoroshiExecutionContext

    batchSubscriptionsDeletion(0, plan)
      .map(_ => api)
  }

  private def batchSubscriptionsUpdates(
      page: Int,
      plan: ApiDocumentationPlan,
      api: Api
  )(implicit env: Env): Future[Unit] = {

    implicit val ec = env.otoroshiExecutionContext

    val pageSize = 50

    env.datastores.apiSubscriptionDataStore
      .streamedFindAndMat(
        _.planRef == plan.id,
        fetchSize = pageSize,
        page = page
      )(ec, env.otoroshiMaterializer, env)
      .flatMap { subscriptions =>
        if (subscriptions.isEmpty) {
          Future.unit
        } else {
          for {
            _ <- Future.traverse(subscriptions) { subscription =>
                   ApiSubscription.handleSubscriptionChanged(api, plan, subscription, Update)
                 }
            _ <- if (subscriptions.size < pageSize) {
                   Future.unit
                 } else {
                   batchSubscriptionsUpdates(page + 1, plan, api)
                 }
          } yield ()
        }
      }
  }

  def updateSubscriptionsByPlan(api: Api, plan: ApiDocumentationPlan)(implicit env: Env): Future[Api] = {
    implicit val ec = env.otoroshiExecutionContext

    batchSubscriptionsUpdates(0, plan, api)
      .map(_ => api)
  }
}

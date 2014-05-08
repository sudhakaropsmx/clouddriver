package com.netflix.oort.controllers

import com.netflix.oort.clusters.Cluster
import com.netflix.oort.deployables.Deployable
import com.netflix.oort.deployables.DeployableProvider
import com.netflix.oort.remoting.RemoteResource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*
import rx.schedulers.Schedulers

@RestController
@RequestMapping("/deployables")
class DeployableController {

  @Autowired
  RemoteResource bakery

  @Autowired
  List<DeployableProvider> deployableProviders

  @RequestMapping(method = RequestMethod.GET)
  def list() {
    Map<String, Deployable> deployables = [:]
    deployableProviders.each {
      it.list().each { Deployable deployable ->
        if (deployables.containsKey(deployable.name)) {
          def existing = deployables[deployable.name]
          def merged = Deployable.merge(existing, deployable)
          deployables[deployable.name] = merged
        } else {
          deployables[deployable.name] = deployable
        }
      }
    }
    deployables.inject(new HashMap()) { Map map, String name, Deployable deployable ->
      if (!map.containsKey(name)) {
        map[name] = [instanceCount: 0, serverGroupCount: 0, attributes: deployable.attributes]
      }
      deployable.clusters.list().each { Cluster cluster ->
        map[name].serverGroupCount += cluster.serverGroups?.size()
        map[name].instanceCount += cluster.serverGroups?.collect { it.getInstanceCount() }?.sum() ?: 0
      }
      map
    }
  }

  @RequestMapping(value = "/{name}/images")
  def getImages(@PathVariable("name") String name) {
    rx.Observable.from(["us-east-1", "us-west-1", "us-west-2", "eu-west-1"]).flatMap {
      rx.Observable.from(it).observeOn(Schedulers.io()).map { String region ->
        def list = bakery.query("/api/v1/${region}/bake/;package=${name};store_type=ebs;region=${region};vm_type=pv;base_os=ubuntu;base_label=release")
        list.findAll { it.ami } collect {
          def version = it.ami_name?.split('-')?.getAt(1..2)?.join('.')
          [name: it.ami, region: region, version: version]
        }
      }
    }.reduce([], { objs, obj ->
      if (obj) {
        objs << obj
      }
      objs
    }).toBlockingObservable().first()?.flatten()
  }



}

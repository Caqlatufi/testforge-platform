import { catalogFeature } from '../catalog'
import { projectsFeature } from '../projects'
import { reportsFeature } from '../reports'
import { runsFeature } from '../runs'
import { workersFeature } from '../workers'
import { jobsFeature } from '../jobs'
import { environmentsFeature } from '../environments'

export const featureModules = [
  jobsFeature,
  runsFeature,
  projectsFeature,
  environmentsFeature,
  catalogFeature,
  workersFeature,
  reportsFeature,
] as const

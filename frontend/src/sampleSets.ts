/**
 * Real FNOL photo sets (see demo-scenarios/sample-images/CREDITS.md), served from
 * demo-scenarios/sample-images (Vite publicDir). Loading a set fills the claim form as a claimant would;
 * the AI then analyses the actual photos (with Gemini enabled).
 * - Vehicles: every side of one damaged car plus a close-up and the VIN label (NHTSA crash tests).
 * - Homes: an overview, a close-up and sometimes the source of the damage (FEMA and CC0 photos), filed on
 *   the home policy, one per cause of loss.
 */
export type SampleSet = {
  folder: string;
  label: string;
  description: string;
  claimedAmount: string;
  line: 'AUTO' | 'HOME';
};

/** File name of each slot's photo in a vehicle set folder. */
export const SET_FILES: Record<string, string> = {
  FRONT: 'front.jpg',
  REAR: 'rear.jpg',
  LEFT: 'left.jpg',
  RIGHT: 'right.jpg',
  CLOSE_UP: 'close-up.jpg',
  ODOMETER_VIN: 'vin-label.jpg',
};

/** File name of each slot's photo in a home set folder; the source photo is optional. */
export const HOME_SET_FILES: Record<string, string> = {
  OVERVIEW: 'overview.jpg',
  CLOSE_UP: 'close-up.jpg',
  OTHER: 'source.jpg',
};

export const homeSampleSets: SampleSet[] = [
  {
    folder: 'fnol-set-home-burst-pipe',
    label: 'Burst water pipe inside a wall',
    description: 'A water pipe burst inside the wall between the bathroom and the study. Water soaked the wall and '
      + 'the floor before I could shut the main valve; the plumber had to open the wall to reach the pipe.',
    claimedAmount: '4800',
    line: 'HOME',
  },
  {
    folder: 'fnol-set-home-tree-on-roof',
    label: 'Storm brought two trees down on the house',
    description: 'During last night\'s storm two large oak trees came down. One fell across the roof over the front '
      + 'rooms and its root ball tore up the garden and the fence.',
    claimedAmount: '22000',
    line: 'HOME',
  },
  {
    folder: 'fnol-set-home-hurricane-roof',
    label: 'Hurricane tore the roof off',
    description: 'The hurricane tore the roof off the upper floor and part of the top storey collapsed. Rain is '
      + 'getting into the house.',
    claimedAmount: '85000',
    line: 'HOME',
  },
  {
    folder: 'fnol-set-home-wildfire',
    label: 'Wildfire destroyed the house',
    description: 'The wildfire came over the ridge while we were evacuated. The house burned down completely; only '
      + 'the chimney and debris are left.',
    claimedAmount: '340000',
    line: 'HOME',
  },
  {
    folder: 'fnol-set-home-river-flood',
    label: 'River flood around the house',
    description: 'After days of rain the river rose overnight. The whole house is standing in water up to the '
      + 'windows and the ground floor is flooded.',
    claimedAmount: '60000',
    line: 'HOME',
  },
];

export const vehicleSampleSets: SampleSet[] = [
  {
    folder: 'fnol-set-corolla-side-impact',
    label: '2020 Toyota Corolla — hit on the driver side',
    description: 'Another car ran the red light and hit my car on the driver side at the intersection. Both left doors '
      + 'are pushed in, the side airbags went off and the driver door will not open.',
    claimedAmount: '9500',
    line: 'AUTO',
  },
  {
    folder: 'fnol-set-camry-frontal',
    label: '2018 Toyota Camry — front-end collision',
    description: 'Traffic stopped suddenly on the highway and I could not brake in time. I hit the back of a stopped '
      + 'truck head-on; the bonnet is crushed, the front airbags deployed and the car is not drivable.',
    claimedAmount: '14000',
    line: 'AUTO',
  },
  {
    folder: 'fnol-set-civic-rear-impact',
    label: '2017 Honda Civic — rear-ended',
    description: 'I was stopped at a red light when a van hit me from behind at speed. The boot and rear bumper are '
      + 'pushed in to the back seats and the boot will not open.',
    claimedAmount: '12000',
    line: 'AUTO',
  },
  {
    folder: 'fnol-set-equinox-side-pole',
    label: '2018 Chevrolet Equinox — slid into a pole',
    description: 'The car slid on a wet road and hit a utility pole with the driver side, just behind the front wheel. '
      + 'The driver door is caved in and the curtain airbag deployed.',
    claimedAmount: '11000',
    line: 'AUTO',
  },
  {
    folder: 'fnol-set-altima-offset-frontal',
    label: '2017 Nissan Altima — oncoming car hit the front-left corner',
    description: 'An oncoming car crossed the centre line and hit the front-left corner of my car. The front left wheel '
      + 'is pushed back, the bonnet is folded and the airbags deployed.',
    claimedAmount: '25000',
    line: 'AUTO',
  },
  {
    folder: 'fnol-set-f150-frontal',
    label: '2018 Ford F-150 pickup — hit a concrete barrier',
    description: 'I lost control on the highway and drove straight into a concrete barrier. The whole front end is '
      + 'crushed, the radiator is leaking and the airbags deployed.',
    claimedAmount: '18000',
    line: 'AUTO',
  },
];

export const sampleSets: SampleSet[] = [...vehicleSampleSets, ...homeSampleSets];
